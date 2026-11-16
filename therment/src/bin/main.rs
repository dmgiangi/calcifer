use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, Instant};

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

mod network;

mod mqtt_credentials {
    include!(concat!(env!("OUT_DIR"), "/mqtt_credentials.rs"));
}

mod wifi_credentials {
    include!(concat!(env!("OUT_DIR"), "/wifi_credentials.rs"));
}

fn onewire_reset(
    pin: &mut PinDriver<'_, esp_idf_hal::gpio::InputOutput>,
) -> Result<bool, EspError> {
    pin.set_low()?;
    Ets::delay_us(480);
    pin.set_high()?;
    Ets::delay_us(70);
    let presence = pin.is_low();
    Ets::delay_us(410);
    Ok(presence)
}

fn onewire_write_byte(
    pin: &mut PinDriver<'_, esp_idf_hal::gpio::InputOutput>,
    byte: u8,
) -> Result<(), EspError> {
    for bit in 0..8 {
        pin.set_low()?;
        if (byte >> bit) & 1 == 1 {
            Ets::delay_us(6);
            pin.set_high()?;
            Ets::delay_us(64);
        } else {
            Ets::delay_us(60);
            pin.set_high()?;
            Ets::delay_us(10);
        }
    }
    Ok(())
}

fn onewire_read_byte(
    pin: &mut PinDriver<'_, esp_idf_hal::gpio::InputOutput>,
) -> Result<u8, EspError> {
    let mut byte = 0;
    for bit in 0..8 {
        pin.set_low()?;
        Ets::delay_us(6);
        pin.set_high()?;
        Ets::delay_us(9);
        if pin.is_high() {
            byte |= 1 << bit;
        }
        Ets::delay_us(55);
    }
    Ok(byte)
}

// A single externally powered DS18B20 is expected on the bus.
fn read_temperature(
    pin: &mut PinDriver<'_, esp_idf_hal::gpio::InputOutput>,
) -> Result<Option<f32>, EspError> {
    if !onewire_reset(pin)? {
        return Ok(None);
    }
    onewire_write_byte(pin, 0xcc)?; // Skip ROM
    onewire_write_byte(pin, 0x44)?; // Convert temperature
    std::thread::sleep(Duration::from_millis(750));
    if !onewire_reset(pin)? {
        return Ok(None);
    }
    onewire_write_byte(pin, 0xcc)?;
    onewire_write_byte(pin, 0xbe)?; // Read scratchpad
    let mut data = [0u8; 9];
    for byte in &mut data {
        *byte = onewire_read_byte(pin)?;
    }
    let mut crc = 0u8;
    for byte in &data[..8] {
        let mut value = *byte;
        for _ in 0..8 {
            let mix = (crc ^ value) & 1;
            crc >>= 1;
            if mix != 0 {
                crc ^= 0x8c;
            }
            value >>= 1;
        }
    }
    if crc != data[8] || data.iter().all(|byte| *byte == 0) {
        return Ok(None);
    }
    let temp = i16::from_le_bytes([data[0], data[1]]) as f32 / 16.0;
    if !(-55.0..=125.0).contains(&temp) {
        return Ok(None);
    }
    Ok(Some(temp))
}

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
enum Mode {
    Off,
    Heating,
    Cooling,
}

#[derive(Clone, Copy, PartialEq)]
struct Snapshot {
    setpoint: f32,
    lower_delta: f32,
    upper_delta: f32,
    cooling_wait: bool,
    enabled: bool,
    action: &'static str,
    temperature: Option<f32>,
    revision: u64,
}
enum Command {
    Setpoint(String),
    Mode(String),
    Delta(bool, String),
}

const DEFAULT_SETPOINT: f32 = 16.0;
const DEFAULT_DELTA: u8 = 5; // tenths of a degree
const COMPRESSOR_DELAY: Duration = Duration::from_secs(300);

fn main() -> Result<(), Box<dyn std::error::Error>> {
    esp_idf_hal::sys::link_patches();
    EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;

    let mut relay_17 = PinDriver::output(peripherals.pins.gpio17)?;
    let mut relay_5 = PinDriver::output(peripherals.pins.gpio5)?;
    // Common relay modules are active-low, so LOW energizes each relay.
    relay_17.set_high()?;
    relay_5.set_high()?;
    let mut sensor = PinDriver::input_output_od(peripherals.pins.gpio13, Pull::Up)?;

    let system_event_loop = EspSystemEventLoop::take()?;
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
    let snapshot = Arc::new(Mutex::new(Snapshot {
        setpoint,
        lower_delta: f32::from(lower_delta) / 10.0,
        upper_delta: f32::from(upper_delta) / 10.0,
        cooling_wait: false,
        enabled,
        action: "idle",
        temperature: None,
        revision: 0,
    }));
    let (tx, rx) = mpsc::channel();
    let network_snapshot = snapshot.clone();
    std::thread::Builder::new()
        .stack_size(12000)
        .spawn(move || {
            if let Err(error) = network::run(
                peripherals.modem,
                system_event_loop,
                nvs,
                tx,
                network_snapshot,
            ) {
                warn!("Network stopped: {error}");
            }
        })?;

    let mut last_cooling_stop = Instant::now();
    let mut mode = Mode::Off;
    let mut last_read = Instant::now() - Duration::from_secs(5);
    let mut temperature = None;
    let mut revision = 0u64;
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
            }
            revision = revision.wrapping_add(1);
        }
        if last_read.elapsed() >= Duration::from_secs(5) {
            temperature = match read_temperature(&mut sensor) {
                Ok(Some(value)) => Some(value),
                Ok(None) => {
                    warn!("Invalid or missing temperature reading");
                    None
                }
                Err(error) => {
                    warn!("OneWire error: {error}");
                    None
                }
            };
            last_read = Instant::now();
        }
        let next = match (enabled, temperature) {
            (true, Some(temp)) => match mode {
                Mode::Heating if temp < setpoint => Mode::Heating,
                Mode::Cooling if temp > setpoint => Mode::Cooling,
                Mode::Off if temp <= setpoint - f32::from(lower_delta) / 10.0 => Mode::Heating,
                Mode::Off
                    if temp >= setpoint + f32::from(upper_delta) / 10.0
                        && last_cooling_stop.elapsed() >= COMPRESSOR_DELAY =>
                {
                    Mode::Cooling
                }
                _ => Mode::Off,
            },
            _ => Mode::Off,
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
            revision,
        };
        std::thread::sleep(Duration::from_millis(250));
    }
}
