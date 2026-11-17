use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, Instant};

use crate::control_policy::Mode;
use esp_idf_hal::delay::Ets;
use esp_idf_hal::gpio::{PinDriver, Pull};
use esp_idf_hal::peripherals::Peripherals;
use esp_idf_hal::sys::EspError;
use esp_idf_svc::log::EspLogger;
use esp_idf_svc::{
    eventloop::EspSystemEventLoop,
    nvs::{EspDefaultNvs, EspDefaultNvsPartition},
};
use log::{info, warn};

#[cfg(all(feature = "ota", not(esp_idf_bootloader_app_rollback_enable)))]
compile_error!("OTA builds require an ESP-IDF bootloader with rollback enabled");
#[cfg(all(feature = "ota", not(esp_idf_esp_task_wdt_panic)))]
compile_error!("OTA builds require a restarting task watchdog for startup");

mod control_policy;
mod network;
mod ota_http;
mod ota_policy;
#[cfg(feature = "ota")]
mod ota_runtime;
mod probes;

#[cfg(feature = "ota")]
mod ota_credentials {
    include!(concat!(env!("OUT_DIR"), "/ota_credentials.rs"));
}

mod mqtt_credentials {
    include!(concat!(env!("OUT_DIR"), "/mqtt_credentials.rs"));
}

mod wifi_credentials {
    include!(concat!(env!("OUT_DIR"), "/wifi_credentials.rs"));
}

#[cfg(feature = "ota")]
const FIRMWARE_VERSION: &str = ota_credentials::RELEASE_VERSION;
#[cfg(not(feature = "ota"))]
const FIRMWARE_VERSION: &str = env!("CARGO_PKG_VERSION");

struct ProbeBus<'a, 'd> {
    pin: &'a mut PinDriver<'d, esp_idf_hal::gpio::InputOutput>,
}

impl probes::Bus for ProbeBus<'_, '_> {
    type Error = EspError;

    fn reset(&mut self) -> Result<bool, EspError> {
        probes::timing::reset(self)
    }

    fn write_bit(&mut self, bit: bool) -> Result<(), EspError> {
        probes::timing::write_bit(self, bit)
    }

    fn read_bit(&mut self) -> Result<bool, EspError> {
        probes::timing::read_bit(self)
    }

    fn wait_conversion(&mut self) {
        std::thread::sleep(Duration::from_millis(750));
    }
}

impl probes::timing::Io for ProbeBus<'_, '_> {
    type Error = EspError;

    #[inline(always)]
    fn set_low(&mut self) -> Result<(), EspError> {
        self.pin.set_low()
    }
    #[inline(always)]
    fn set_high(&mut self) -> Result<(), EspError> {
        self.pin.set_high()
    }
    #[inline(always)]
    fn is_high(&self) -> bool {
        self.pin.is_high()
    }
    #[inline(always)]
    fn delay_us(&mut self, us: u32) {
        Ets::delay_us(us);
    }
    #[inline(always)]
    fn critical<R>(
        &mut self,
        operation: impl FnOnce(&mut Self) -> Result<R, EspError>,
    ) -> Result<R, EspError> {
        esp_idf_hal::interrupt::free(|| operation(self))
    }
}

#[derive(Clone, PartialEq)]
struct Snapshot {
    setpoint: f32,
    lower_delta: f32,
    upper_delta: f32,
    cooling_wait: bool,
    enabled: bool,
    action: &'static str,
    temperature: Option<f32>,
    probes: Vec<probes::MonitoredProbe>,
    diagnostics: probes::diagnostics::Diagnostics,
    control_probe: Option<probes::Rom>,
    revision: u64,
}
enum Command {
    Setpoint(String),
    Mode(String),
    Delta(bool, String),
    ControlProbe(String),
    #[cfg(feature = "ota")]
    OtaReady(mpsc::Sender<()>),
    #[cfg(feature = "ota")]
    OtaResume,
}

const DEFAULT_SETPOINT: f32 = 16.0;
const DEFAULT_DELTA: u8 = 5; // tenths of a degree
const PROBE_SAMPLE_INTERVAL: Duration = Duration::from_secs(15);
const COMPRESSOR_DELAY: Duration = Duration::from_secs(300);

fn main() -> Result<(), Box<dyn std::error::Error>> {
    esp_idf_hal::sys::link_patches();
    EspLogger::initialize_default();
    let started = Instant::now();

    #[cfg(feature = "ota")]
    EspError::convert(unsafe { esp_idf_hal::sys::esp_task_wdt_add(std::ptr::null_mut()) })?;

    let peripherals = Peripherals::take()?;

    // Preload both GPIO output latches before enabling output drivers. Relay
    // boards are active-low; boot-ROM and reset-time levels still require
    // hardware verification and appropriate external pull-ups.
    EspError::convert(unsafe { esp_idf_hal::sys::gpio_set_level(17, 1) })?;
    EspError::convert(unsafe { esp_idf_hal::sys::gpio_set_level(5, 1) })?;
    let mut relay_17 = PinDriver::output(peripherals.pins.gpio17)?;
    let mut relay_5 = PinDriver::output(peripherals.pins.gpio5)?;
    // Common relay modules are active-low, so LOW energizes each relay.
    relay_17.set_high()?;
    relay_5.set_high()?;
    let mut sensor = PinDriver::input_output_od(peripherals.pins.gpio13, Pull::Up)?;

    let system_event_loop = EspSystemEventLoop::take()?;
    #[cfg(feature = "ota")]
    esp_idf_svc::netif::NetifStack::initialize()?;
    let nvs = EspDefaultNvsPartition::take()?;
    let storage = EspDefaultNvs::new(nvs.clone(), "therment", true)?;
    let mut setpoint = storage
        .get_u8("setpoint")?
        .filter(|v| (10..=60).contains(v))
        .map(|v| f32::from(v) / 2.0)
        .unwrap_or(DEFAULT_SETPOINT);
    let mut lower_delta = storage
        .get_u8("delta_low")?
        .filter(|v| (1..=50).contains(v))
        .unwrap_or(DEFAULT_DELTA);
    let mut upper_delta = storage
        .get_u8("delta_high")?
        .filter(|v| (1..=50).contains(v))
        .unwrap_or(DEFAULT_DELTA);
    let mut enabled = storage.get_u8("enabled")? == Some(1);
    let mut control_probe = storage
        .get_u64("control_probe")?
        .and_then(probes::Rom::from_storage);
    if control_probe.is_none() && enabled {
        // Migration never guesses which probe belongs to the chamber.
        enabled = false;
        storage.set_u8("enabled", 0)?;
    }
    let snapshot = Arc::new(Mutex::new(Snapshot {
        setpoint,
        lower_delta: f32::from(lower_delta) / 10.0,
        upper_delta: f32::from(upper_delta) / 10.0,
        cooling_wait: false,
        enabled,
        action: "idle",
        temperature: None,
        probes: Vec::new(),
        diagnostics: probes::diagnostics::Diagnostics::default(),
        control_probe,
        revision: 0,
    }));
    let (tx, rx) = mpsc::channel();
    #[cfg(feature = "ota")]
    let (network_ready_tx, network_ready_rx) = mpsc::sync_channel(1);
    #[cfg(feature = "ota")]
    let mut network_ready = Some(network_ready_tx);
    #[cfg(feature = "ota")]
    let (ota_trigger_tx, ota_trigger_rx) = mpsc::sync_channel(1);
    #[cfg(feature = "ota")]
    let ota_status = Arc::new(Mutex::new(ota_runtime::Status::default()));
    #[cfg(feature = "ota")]
    let ota_network_status = ota_status.clone();
    #[cfg(feature = "ota")]
    let ota_command_tx = tx.clone();
    #[cfg(feature = "ota")]
    let ota_nvs = nvs.clone();
    let network_snapshot = snapshot.clone();
    std::thread::Builder::new()
        .stack_size(12000)
        .spawn(move || {
            #[cfg(feature = "ota")]
            if network_ready_rx.recv().is_err() {
                return;
            }
            if let Err(error) = network::run(
                peripherals.modem,
                system_event_loop,
                nvs,
                tx,
                network_snapshot,
                #[cfg(feature = "ota")]
                ota_trigger_tx,
                #[cfg(feature = "ota")]
                ota_network_status,
            ) {
                warn!("Network stopped: {error}");
            }
        })?;

    let mut last_cooling_stop = Instant::now();
    let mut mode = Mode::Off;
    let mut last_read = Instant::now() - PROBE_SAMPLE_INTERVAL;
    let mut temperature = None;
    let mut probe_readings = Vec::new();
    let mut probe_monitor = probes::ProbeMonitor::new(control_probe);
    let mut diagnostics = probes::diagnostics::Diagnostics::default();
    let mut revision = 0u64;
    #[cfg(feature = "ota")]
    let mut ota_waiting: Option<mpsc::Sender<()>> = None;
    #[cfg(feature = "ota")]
    let mut ota_hold = false;
    #[cfg(feature = "ota")]
    let mut ota_startup = Some((ota_trigger_rx, ota_nvs, ota_command_tx, ota_status));
    loop {
        while let Ok(command) = rx.try_recv() {
            match command {
                Command::Setpoint(raw) => {
                    let requested = raw.trim().parse::<f32>().ok().filter(|v| {
                        v.is_finite() && (5.0..=30.0).contains(v) && (v * 2.0).fract() == 0.0
                    });
                    match requested {
                        Some(value) if value == setpoint => {
                            info!("MQTT setpoint unchanged: {setpoint:.1} C")
                        }
                        Some(value) => match storage.set_u8("setpoint", (value * 2.0) as u8) {
                            Ok(()) => {
                                info!("MQTT setpoint: {setpoint:.1} -> {value:.1} C");
                                setpoint = value;
                            }
                            Err(error) => warn!("MQTT setpoint rejected: storage error: {error}"),
                        },
                        None => warn!("MQTT setpoint rejected: expected 5–30 C in 0.5 C steps"),
                    }
                }
                Command::Delta(is_upper, raw) => {
                    let label = if is_upper { "upper" } else { "lower" };
                    let requested = raw.trim().parse::<f32>().ok().filter(|v| {
                        let scaled = v * 10.0;
                        v.is_finite()
                            && (0.1..=5.0).contains(v)
                            && (scaled - scaled.round()).abs() < 0.0001
                    });
                    match requested {
                        Some(value) => {
                            let value = (value * 10.0).round() as u8;
                            let (key, current) = if is_upper {
                                ("delta_high", &mut upper_delta)
                            } else {
                                ("delta_low", &mut lower_delta)
                            };
                            if value == *current {
                                info!(
                                    "MQTT {label} delta unchanged: {:.1} C",
                                    f32::from(value) / 10.0
                                );
                            } else {
                                match storage.set_u8(key, value) {
                                    Ok(()) => {
                                        info!(
                                            "MQTT {label} delta: {:.1} -> {:.1} C",
                                            f32::from(*current) / 10.0,
                                            f32::from(value) / 10.0
                                        );
                                        *current = value;
                                    }
                                    Err(error) => {
                                        warn!("MQTT {label} delta rejected: storage error: {error}")
                                    }
                                }
                            }
                        }
                        None => {
                            warn!("MQTT {label} delta rejected: expected 0.1–5 C in 0.1 C steps")
                        }
                    }
                }
                Command::Mode(value) => {
                    let requested = match value.as_str() {
                        "auto" => Some(true),
                        "off" => Some(false),
                        _ => None,
                    };
                    match requested {
                        Some(true) if control_probe.is_none() => {
                            warn!("MQTT auto mode rejected: select a control probe first");
                        }
                        Some(requested) if requested == enabled => info!(
                            "MQTT mode unchanged: {}",
                            if enabled { "auto" } else { "off" }
                        ),
                        Some(requested) => {
                            // An off command always turns outputs off, even if storage fails.
                            if !requested {
                                enabled = false;
                            }
                            match storage.set_u8("enabled", requested as u8) {
                                Ok(()) => {
                                    enabled = requested;
                                    info!(
                                        "MQTT mode set: {}",
                                        if enabled { "auto" } else { "off" }
                                    );
                                }
                                Err(error) => warn!(
                                    "MQTT mode persistence failed: {error}; current mode: {}",
                                    if enabled { "auto" } else { "off" }
                                ),
                            }
                        }
                        None => warn!("MQTT mode rejected: expected auto or off"),
                    }
                }
                Command::ControlProbe(raw) => {
                    match probes::selection_request(
                        &raw,
                        enabled,
                        mode == Mode::Off,
                        &probe_readings,
                    ) {
                        Ok(selected) if selected == control_probe => {
                            info!("MQTT control probe unchanged");
                        }
                        Ok(selected) => {
                            let stored = selected.map_or(0, probes::Rom::storage_value);
                            match storage.set_u64("control_probe", stored) {
                                Ok(()) => {
                                    control_probe = selected;
                                    temperature = None;
                                    probe_monitor.reset_probe(selected);
                                    // Require a fresh sample and a full restart delay
                                    // before control can use the new association.
                                    last_read = Instant::now() - PROBE_SAMPLE_INTERVAL;
                                    last_cooling_stop = Instant::now();
                                    info!("MQTT control probe association saved");
                                }
                                Err(error) => {
                                    warn!("MQTT control probe rejected: storage error: {error}")
                                }
                            }
                        }
                        Err(reason) => warn!("MQTT control probe rejected: {reason}"),
                    }
                }
                #[cfg(feature = "ota")]
                Command::OtaReady(reply) => {
                    ota_waiting = Some(reply);
                }
                #[cfg(feature = "ota")]
                Command::OtaResume => {
                    ota_waiting = None;
                    ota_hold = false;
                }
            }
            revision = revision.wrapping_add(1);
        }
        if last_read.elapsed() >= PROBE_SAMPLE_INTERVAL {
            let sample_started = Instant::now();
            let mut cycle = probes::diagnostics::SampleDiagnostics::default();
            probe_readings = match probes::sample(&mut ProbeBus { pin: &mut sensor }, &mut cycle) {
                Ok(readings) => readings,
                Err(error) => {
                    warn!("OneWire error: {error}");
                    Vec::new()
                }
            };
            probe_monitor.update(&probe_readings);
            diagnostics.update(
                &cycle,
                probe_monitor.readings(),
                started.elapsed().as_secs(),
                sample_started
                    .elapsed()
                    .as_millis()
                    .min(u128::from(u64::MAX)) as u64,
            );
            if let Some(reason) = diagnostics.current_error {
                warn!(
                    "Probe sample {}: {} (failed cycles {})",
                    diagnostics.cycles,
                    reason.as_str(),
                    diagnostics.failed_cycles
                );
            }
            temperature = probe_monitor.temperature(control_probe);
            if control_probe.is_some() && temperature.is_none() {
                warn!("Control probe has no usable reading; outputs inhibited");
            }
            last_read = Instant::now();
        }
        let next = control_policy::next_mode(
            enabled,
            mode,
            temperature,
            setpoint,
            f32::from(lower_delta) / 10.0,
            f32::from(upper_delta) / 10.0,
            last_cooling_stop.elapsed() >= COMPRESSOR_DELAY,
        );
        #[cfg(feature = "ota")]
        let next = if ota_hold || ota_startup.is_some() {
            Mode::Off
        } else {
            next
        };
        if next != mode {
            relay_17.set_high()?;
            relay_5.set_high()?;
            if mode == Mode::Cooling {
                last_cooling_stop = Instant::now();
            }
            match next {
                Mode::Heating => relay_17.set_low()?,
                Mode::Cooling => relay_5.set_low()?,
                Mode::Off => (),
            }
            mode = next;
            info!("Control mode: {mode:?}");
        }
        let cooling_wait = enabled
            && temperature.is_some_and(|temp| temp >= setpoint + f32::from(upper_delta) / 10.0)
            && mode == Mode::Off
            && last_cooling_stop.elapsed() < COMPRESSOR_DELAY;
        *snapshot.lock().unwrap() = Snapshot {
            setpoint,
            lower_delta: f32::from(lower_delta) / 10.0,
            upper_delta: f32::from(upper_delta) / 10.0,
            cooling_wait,
            enabled,
            action: match mode {
                Mode::Heating => "heating",
                Mode::Cooling => "cooling",
                Mode::Off => "idle",
            },
            temperature,
            probes: probe_monitor.readings().to_vec(),
            diagnostics: diagnostics.clone(),
            control_probe,
            revision,
        };
        #[cfg(feature = "ota")]
        if ota_policy::may_activate(mode == Mode::Heating, mode == Mode::Cooling, true)
            && let Some(reply) = ota_waiting.take()
        {
            if reply.send(()).is_ok() {
                ota_hold = true;
            }
        }
        #[cfg(feature = "ota")]
        if let Some((trigger, nvs, commands, status)) = ota_startup.take() {
            #[cfg(feature = "ota-test-boot-failure")]
            if ota_runtime::running_unverified()? {
                warn!("Intentional rollback test: leaving the image unconfirmed");
                loop {
                    std::thread::sleep(Duration::from_secs(1));
                }
            }
            // Confirm only after GPIO, NVS and the first local control cycle
            // succeeded. A missing sensor remains safely off, not a boot failure.
            ota_runtime::confirm_running_image(&storage)?;
            EspError::convert(unsafe {
                esp_idf_hal::sys::esp_task_wdt_delete(std::ptr::null_mut())
            })?;
            if let Some(ready) = network_ready.take() {
                ready.send(())?;
            }
            std::thread::Builder::new()
                .stack_size(24_576)
                .spawn(move || {
                    ota_runtime::run(trigger, nvs, commands, status);
                })?;
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}
