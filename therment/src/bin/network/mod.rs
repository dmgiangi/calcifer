mod network_diagnostics;
mod probe_mqtt;

use crate::{Command, Snapshot, mqtt_credentials, wifi_credentials};
use esp_idf_svc::eventloop::EspSystemEventLoop;
use esp_idf_svc::hal::modem::Modem;
use esp_idf_svc::handle::RawHandle;
use esp_idf_svc::mqtt::client::{
    Details, EspMqttClient, EventPayload, LwtConfiguration, MqttClientConfiguration, QoS,
};
use esp_idf_svc::nvs::EspDefaultNvsPartition;
use esp_idf_svc::sys;
use esp_idf_svc::wifi::{ClientConfiguration, Configuration, EspWifi, WifiEvent};
use log::{info, warn};
use network_diagnostics::{MqttError, NetworkDiagnostics, STATE_TOPIC as NETWORK_DIAGNOSTICS};
use probe_mqtt::{
    CONTROL_PROBE_DISCOVERY, CONTROL_PROBE_SET, CONTROL_PROBE_STATE, PROBES_STATE,
    PROBES_STATUS_STATE,
};
use std::collections::BTreeSet;
use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, Instant};

const TEMP: &str = "therment/therment-01/temperature";
const STATE: &str = "therment/therment-01/state";
const AVAIL: &str = "therment/therment-01/availability";
const SETPOINT: &str = "therment/therment-01/setpoint/set";
const LOWER: &str = "therment/therment-01/delta_lower/set";
const UPPER: &str = "therment/therment-01/delta_upper/set";
const MODE: &str = "therment/therment-01/mode/set";
const DISCOVERY_TOPIC: &str = "homeassistant/climate/therment-01/config";
const DISCOVERY: &str = r#"{"name":"Cella fermentazione","unique_id":"therment-01-climate","device":{"identifiers":["therment-01"],"name":"Therment"},"modes":["auto","off"],"mode_command_topic":"therment/therment-01/mode/set","mode_state_topic":"therment/therment-01/state","mode_state_template":"{{ value_json.mode }}","temperature_command_topic":"therment/therment-01/setpoint/set","temperature_state_topic":"therment/therment-01/state","temperature_state_template":"{{ value_json.setpoint }}","current_temperature_topic":"therment/therment-01/temperature","action_topic":"therment/therment-01/state","action_template":"{{ value_json.action }}","availability_topic":"therment/therment-01/availability","payload_available":"online","payload_not_available":"offline","min_temp":5,"max_temp":30,"temp_step":0.5,"temperature_unit":"C","retain":false,"optimistic":false}"#;

const LOWER_DISCOVERY: &str = r#"{"name":"Delta inferiore","unique_id":"therment-01-delta-lower","device":{"identifiers":["therment-01"]},"command_topic":"therment/therment-01/delta_lower/set","state_topic":"therment/therment-01/state","value_template":"{{ value_json.delta_lower }}","availability_topic":"therment/therment-01/availability","min":0.1,"max":5,"step":0.1,"unit_of_measurement":"°C","mode":"box","retain":false,"optimistic":false}"#;
const UPPER_DISCOVERY: &str = r#"{"name":"Delta superiore","unique_id":"therment-01-delta-upper","device":{"identifiers":["therment-01"]},"command_topic":"therment/therment-01/delta_upper/set","state_topic":"therment/therment-01/state","value_template":"{{ value_json.delta_upper }}","availability_topic":"therment/therment-01/availability","min":0.1,"max":5,"step":0.1,"unit_of_measurement":"°C","mode":"box","retain":false,"optimistic":false}"#;

const WAIT_DISCOVERY: &str = r#"{"name":"Attesa compressore","unique_id":"therment-01-compressor-wait","device":{"identifiers":["therment-01"]},"state_topic":"therment/therment-01/state","value_template":"{{ 'ON' if value_json.cooling_wait else 'OFF' }}","availability_topic":"therment/therment-01/availability","icon":"mdi:timer-sand"}"#;

#[cfg(feature = "ota")]
const OTA_DISCOVERY: &str = r#"{"name":"Firmware","unique_id":"therment-01-ota","device":{"identifiers":["therment-01"]},"device_class":"firmware","state_topic":"therment/therment-01/ota/state","json_attributes_topic":"therment/therment-01/ota/attributes","command_topic":"therment/therment-01/ota/set","payload_install":"INSTALL","availability_topic":"therment/therment-01/availability","retain":false}"#;

#[cfg(feature = "ota")]
const OTA_CHECK_DISCOVERY: &str = r#"{"name":"Check for updates","unique_id":"therment-01-ota-check","device":{"identifiers":["therment-01"]},"entity_category":"diagnostic","command_topic":"therment/therment-01/ota/set","payload_press":"INSTALL","availability_topic":"therment/therment-01/availability","retain":false}"#;

enum Event {
    Connected,
    Disconnected,
    WifiDisconnected(u16, i8),
    MqttError(MqttError),
    Received(String, Vec<u8>),
}

// embedded-svc 0.29 discards the retain flag in EventPayload::Received. Use the
// native event for this command only; keep the existing callback for all others.
unsafe extern "C" fn control_probe_event(
    argument: *mut std::ffi::c_void,
    _base: sys::esp_event_base_t,
    _id: i32,
    data: *mut std::ffi::c_void,
) {
    // SAFETY: ESP-IDF supplies a valid event for the duration of this callback.
    let event = unsafe { &*data.cast::<sys::esp_mqtt_event_t>() };
    if event.retain
        || event.topic.is_null()
        || event.topic_len != CONTROL_PROBE_SET.len() as i32
        || event.data.is_null()
        || !(1..=64).contains(&event.data_len)
        || event.current_data_offset != 0
        || event.data_len != event.total_data_len
    {
        return;
    }
    // SAFETY: The non-null event buffers have the lengths checked above and are
    // borrowed only during this callback. The boxed sender outlives the client.
    let (topic, payload, sender) = unsafe {
        (
            std::slice::from_raw_parts(event.topic.cast::<u8>(), event.topic_len as usize),
            std::slice::from_raw_parts(event.data.cast::<u8>(), event.data_len as usize),
            &*argument.cast::<mpsc::Sender<Event>>(),
        )
    };
    if topic == CONTROL_PROBE_SET.as_bytes() {
        let _ = sender.send(Event::Received(CONTROL_PROBE_SET.into(), payload.to_vec()));
    }
}

unsafe extern "C" fn mqtt_error_event(
    argument: *mut std::ffi::c_void,
    _base: sys::esp_event_base_t,
    _id: i32,
    data: *mut std::ffi::c_void,
) {
    if argument.is_null() || data.is_null() {
        return;
    }
    // SAFETY: ESP-IDF supplies a valid event for the duration of this callback.
    let event = unsafe { &*data.cast::<sys::esp_mqtt_event_t>() };
    let error = if event.error_handle.is_null() {
        MqttError::from_raw(0, 0, 0, 0, 0, 0)
    } else {
        // SAFETY: The error handle is owned by the event and is read only here.
        let error = unsafe { &*event.error_handle };
        MqttError::from_raw(
            error.error_type,
            error.esp_tls_last_esp_err,
            error.esp_tls_stack_err,
            error.esp_tls_cert_verify_flags,
            error.esp_transport_sock_errno,
            error.connect_return_code,
        )
    };
    // SAFETY: The boxed sender remains alive until after MQTT client destruction.
    let sender = unsafe { &*argument.cast::<mpsc::Sender<Event>>() };
    let _ = sender.send(Event::MqttError(error));
}

pub fn run(
    modem: Modem,
    sys: EspSystemEventLoop,
    nvs: EspDefaultNvsPartition,
    commands: mpsc::Sender<Command>,
    snapshot: Arc<Mutex<Snapshot>>,
    #[cfg(feature = "ota")] ota_trigger: mpsc::SyncSender<()>,
    #[cfg(feature = "ota")] ota_status: Arc<Mutex<crate::ota_runtime::Status>>,
) -> Result<(), Box<dyn std::error::Error>> {
    let started = Instant::now();
    let mut network_diagnostics = NetworkDiagnostics::new(unsafe { sys::esp_reset_reason() });
    let (events_tx, events_rx) = mpsc::channel();
    let wifi_events_tx = events_tx.clone();
    let _wifi_subscription = sys.clone().subscribe::<WifiEvent<'_>, _>(move |event| {
        if let WifiEvent::StaDisconnected(disconnect) = event {
            let _ = wifi_events_tx.send(Event::WifiDisconnected(
                disconnect.reason(),
                disconnect.rssi(),
            ));
        }
    })?;
    let mut wifi = EspWifi::new(modem, sys, Some(nvs))?;
    wifi.set_configuration(&Configuration::Client(ClientConfiguration {
        ssid: wifi_credentials::WIFI_SSID.try_into().unwrap(),
        password: wifi_credentials::WIFI_PASSWORD.try_into().unwrap(),
        ..Default::default()
    }))?;
    while let Err(error) = wifi.start() {
        warn!("Wi-Fi start failed: {error}; retrying");
        std::thread::sleep(Duration::from_secs(5));
    }
    let mut last_wifi_attempt = Instant::now() - Duration::from_secs(5);
    while !wifi.is_up().unwrap_or(false) {
        if !wifi.is_connected().unwrap_or(false)
            && last_wifi_attempt.elapsed() >= Duration::from_secs(5)
        {
            last_wifi_attempt = Instant::now();
            if let Err(error) = wifi.connect() {
                warn!("Wi-Fi connection attempt: {error}");
            }
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    let url = format!(
        "mqtt://{}:{}",
        mqtt_credentials::MQTT_HOST,
        mqtt_credentials::MQTT_PORT
    );
    let config = MqttClientConfiguration {
        client_id: Some("therment-01"),
        reconnect_timeout: Some(Duration::from_secs(5)),
        username: Some(mqtt_credentials::MQTT_USERNAME),
        password: Some(mqtt_credentials::MQTT_PASSWORD),
        lwt: Some(LwtConfiguration {
            topic: AVAIL,
            payload: b"offline",
            qos: QoS::AtLeastOnce,
            retain: true,
        }),
        ..Default::default()
    };
    // Declared before the client so its stable callback context is dropped last.
    let control_events = Box::new(events_tx.clone());
    let mqtt_error_events = Box::new(events_tx.clone());
    let mut client = loop {
        if !wifi.is_up().unwrap_or(false) {
            if !wifi.is_connected().unwrap_or(false)
                && last_wifi_attempt.elapsed() >= Duration::from_secs(5)
            {
                last_wifi_attempt = Instant::now();
                if let Err(error) = wifi.connect() {
                    warn!("Wi-Fi connection attempt: {error}");
                }
            }
            std::thread::sleep(Duration::from_secs(1));
            continue;
        }
        let events_tx = events_tx.clone();
        match EspMqttClient::new_cb(&url, &config, move |event| {
            let message = match event.payload() {
                EventPayload::Connected(_) => Some(Event::Connected),
                EventPayload::Disconnected => Some(Event::Disconnected),
                EventPayload::Received {
                    topic: Some(topic),
                    data,
                    details: Details::Complete,
                    ..
                } if data.len() <= 64 && topic != CONTROL_PROBE_SET => {
                    Some(Event::Received(topic.to_owned(), data.to_vec()))
                }
                _ => None,
            };
            if let Some(message) = message {
                let _ = events_tx.send(message);
            }
        }) {
            Ok(client) => break client,
            Err(error) => {
                warn!("MQTT initialization failed: {error}; retrying");
                std::thread::sleep(Duration::from_secs(5));
            }
        }
    };
    // SAFETY: The boxed context remains alive until after client destruction,
    // which stops MQTT callbacks. No subscription is made before registration.
    sys::esp!(unsafe {
        sys::esp_mqtt_client_register_event(
            RawHandle::handle(&client),
            sys::esp_mqtt_event_id_t_MQTT_EVENT_DATA,
            Some(control_probe_event),
            (&*control_events as *const mpsc::Sender<Event>)
                .cast_mut()
                .cast(),
        )
    })?;
    // SAFETY: The boxed context remains alive until after client destruction.
    sys::esp!(unsafe {
        sys::esp_mqtt_client_register_event(
            RawHandle::handle(&client),
            sys::esp_mqtt_event_id_t_MQTT_EVENT_ERROR,
            Some(mqtt_error_event),
            (&*mqtt_error_events as *const mpsc::Sender<Event>)
                .cast_mut()
                .cast(),
        )
    })?;
    let mut connected = false;
    let mut subscribed = false;
    let mut probe_state_reset = false;
    let mut known_probes = BTreeSet::new();
    let mut discovered_probes = BTreeSet::new();
    let mut last_selector_discovery = None;
    let mut mqtt_started = true;
    let mut last = None;
    let mut network_diagnostics_dirty = true;
    let mut last_network_diagnostics = Instant::now() - Duration::from_secs(30);
    #[cfg(feature = "ota")]
    let mut last_ota = None;
    loop {
        if !wifi.is_connected().unwrap_or(false)
            && last_wifi_attempt.elapsed() >= Duration::from_secs(5)
        {
            last_wifi_attempt = Instant::now();
            if let Err(error) = wifi.connect() {
                warn!("Wi-Fi connection attempt: {error}");
            }
        }
        if wifi.is_up().unwrap_or(false) {
            if !mqtt_started {
                if let Err(error) =
                    sys::esp!(unsafe { sys::esp_mqtt_client_start(RawHandle::handle(&client)) })
                {
                    warn!("MQTT start failed while Wi-Fi is up: {error}; retrying");
                } else {
                    mqtt_started = true;
                    info!("MQTT started after Wi-Fi became available");
                }
            }
        } else if mqtt_started {
            connected = false;
            subscribed = false;
            probe_state_reset = false;
            last = None;
            #[cfg(feature = "ota")]
            {
                last_ota = None;
            }
            if let Err(error) =
                sys::esp!(unsafe { sys::esp_mqtt_client_stop(RawHandle::handle(&client)) })
            {
                warn!("MQTT stop failed while Wi-Fi is down: {error}; retrying");
            } else {
                mqtt_started = false;
                info!("MQTT stopped because Wi-Fi is unavailable");
            }
        }
        while let Ok(event) = events_rx.try_recv() {
            match event {
                Event::Connected if mqtt_started && wifi.is_up().unwrap_or(false) => {
                    info!("MQTT connected");
                    connected = true;
                    network_diagnostics.record_mqtt_connected();
                    network_diagnostics_dirty = true;
                    subscribed = false;
                    probe_state_reset = false;
                    discovered_probes.clear();
                    last_selector_discovery = None;
                    last = None;
                    #[cfg(feature = "ota")]
                    {
                        last_ota = None;
                    }
                }
                Event::Disconnected if mqtt_started => {
                    info!("MQTT disconnected; automatic reconnect enabled");
                    connected = false;
                    network_diagnostics.record_mqtt_disconnected();
                    network_diagnostics_dirty = true;
                    subscribed = false;
                    last = None;
                    #[cfg(feature = "ota")]
                    {
                        last_ota = None;
                    }
                }
                Event::Connected | Event::Disconnected => {}
                Event::WifiDisconnected(reason, rssi) => {
                    network_diagnostics.record_wifi_disconnect(
                        reason,
                        rssi,
                        started.elapsed().as_secs(),
                    );
                    network_diagnostics_dirty = true;
                }
                Event::MqttError(error) => {
                    network_diagnostics.record_mqtt_error(error, started.elapsed().as_secs());
                    network_diagnostics_dirty = true;
                }
                Event::Received(topic, data) => {
                    let value = String::from_utf8_lossy(&data).into_owned();
                    if topic == SETPOINT {
                        let _ = commands.send(Command::Setpoint(value));
                    } else if topic == LOWER || topic == UPPER {
                        let _ = commands.send(Command::Delta(topic == UPPER, value));
                    } else if topic == MODE {
                        let _ = commands.send(Command::Mode(value));
                    } else if topic == CONTROL_PROBE_SET {
                        let _ = commands.send(Command::ControlProbe(value));
                    }
                    #[cfg(feature = "ota")]
                    if topic == "therment/therment-01/ota/set" && data == b"INSTALL" {
                        let _ = ota_trigger.try_send(());
                    }
                    last = None;
                }
            }
        }
        let mqtt_available = mqtt_started && wifi.is_up().unwrap_or(false);
        if mqtt_available && connected && !probe_state_reset {
            // Clear retained readings from previous runs before going online.
            let current = snapshot.lock().unwrap().clone();
            probe_state_reset = publish(&mut client, AVAIL, "offline")
                && publish(&mut client, PROBES_STATE, "{}")
                && publish(&mut client, PROBES_STATUS_STATE, "{}")
                && publish(&mut client, NETWORK_DIAGNOSTICS, "{}")
                && publish(
                    &mut client,
                    probe_mqtt::PROBES_DIAGNOSTICS,
                    &probe_mqtt::diagnostics_state(
                        &current.diagnostics,
                        &current.probes,
                        crate::FIRMWARE_VERSION,
                    )
                    .to_string(),
                );
        }
        if mqtt_available && connected && probe_state_reset && !subscribed {
            let setpoint_ok = client.subscribe(SETPOINT, QoS::AtLeastOnce).is_ok();
            let mode_ok = client.subscribe(MODE, QoS::AtLeastOnce).is_ok();
            let lower_ok = client.subscribe(LOWER, QoS::AtLeastOnce).is_ok();
            let upper_ok = client.subscribe(UPPER, QoS::AtLeastOnce).is_ok();
            let control_probe_ok = client
                .subscribe(CONTROL_PROBE_SET, QoS::AtLeastOnce)
                .is_ok();
            #[cfg(feature = "ota")]
            let ota_ok = client
                .subscribe("therment/therment-01/ota/set", QoS::AtLeastOnce)
                .is_ok()
                && publish(
                    &mut client,
                    "homeassistant/update/therment-01-ota/config",
                    OTA_DISCOVERY,
                )
                && publish(
                    &mut client,
                    "homeassistant/button/therment-01-ota-check/config",
                    OTA_CHECK_DISCOVERY,
                );
            #[cfg(not(feature = "ota"))]
            let ota_ok = true;
            if setpoint_ok
                && mode_ok
                && lower_ok
                && upper_ok
                && control_probe_ok
                && ota_ok
                && publish(&mut client, DISCOVERY_TOPIC, DISCOVERY)
                && publish(
                    &mut client,
                    "homeassistant/number/therment-01-delta-lower/config",
                    LOWER_DISCOVERY,
                )
                && publish(
                    &mut client,
                    "homeassistant/number/therment-01-delta-upper/config",
                    UPPER_DISCOVERY,
                )
                && publish(
                    &mut client,
                    "homeassistant/binary_sensor/therment-01-compressor-wait/config",
                    WAIT_DISCOVERY,
                )
                && publish(
                    &mut client,
                    probe_mqtt::BUS_DIAGNOSTICS_DISCOVERY,
                    &probe_mqtt::bus_diagnostics_discovery().to_string(),
                )
                && network_diagnostics::discovery_configs()
                    .into_iter()
                    .all(|(topic, config)| publish(&mut client, topic, &config.to_string()))
            {
                subscribed = true;
                last = None;
            } else {
                warn!("MQTT setup failed; retrying");
            }
        }
        if mqtt_available && connected && subscribed {
            #[cfg(feature = "ota")]
            {
                let current_ota = ota_status.lock().unwrap().clone();
                if last_ota.as_ref() != Some(&current_ota) {
                    let mut state = serde_json::json!({
                        "installed_version": current_ota.installed,
                        "latest_version": current_ota.latest,
                        "in_progress": current_ota.in_progress,
                    });
                    if let Some(progress) = current_ota.progress {
                        state["update_percentage"] = progress.into();
                    } else if !current_ota.in_progress {
                        state["update_percentage"] = serde_json::Value::Null;
                    }
                    let state = state.to_string();
                    let attributes = serde_json::json!({"phase": current_ota.phase, "outcome": current_ota.outcome}).to_string();
                    if publish(&mut client, "therment/therment-01/ota/state", &state)
                        && publish(
                            &mut client,
                            "therment/therment-01/ota/attributes",
                            &attributes,
                        )
                    {
                        last_ota = Some(current_ota);
                    }
                }
            }
            if network_diagnostics_dirty
                || last_network_diagnostics.elapsed() >= Duration::from_secs(30)
            {
                let wifi_connected = wifi.is_connected().unwrap_or(false);
                let wifi_rssi_dbm = if wifi_connected {
                    wifi.get_ap_info()
                        .ok()
                        .map(|access_point| i32::from(access_point.signal_strength))
                } else {
                    None
                };
                let diagnostics = network_diagnostics
                    .state(
                        crate::FIRMWARE_VERSION,
                        started.elapsed().as_secs(),
                        wifi_connected,
                        wifi_rssi_dbm,
                    )
                    .to_string();
                if publish(&mut client, NETWORK_DIAGNOSTICS, &diagnostics) {
                    network_diagnostics_dirty = false;
                    last_network_diagnostics = Instant::now();
                }
            }
            let current = snapshot.lock().unwrap().clone();
            known_probes.extend(current.probes.iter().map(|probe| probe.rom));
            for &rom in &known_probes {
                if !discovered_probes.contains(&rom)
                    && publish(
                        &mut client,
                        &probe_mqtt::probe_discovery_topic(rom),
                        &probe_mqtt::probe_discovery(rom).to_string(),
                    )
                    && publish(
                        &mut client,
                        &probe_mqtt::probe_status_discovery_topic(rom),
                        &probe_mqtt::probe_status_discovery(rom).to_string(),
                    )
                    && publish(
                        &mut client,
                        &probe_mqtt::probe_diagnostics_discovery_topic(rom),
                        &probe_mqtt::probe_diagnostics_discovery(rom).to_string(),
                    )
                {
                    discovered_probes.insert(rom);
                }
            }
            let selector_discovery =
                probe_mqtt::control_probe_discovery(&current.probes, current.control_probe);
            if last_selector_discovery.as_ref() != Some(&selector_discovery)
                && publish(
                    &mut client,
                    CONTROL_PROBE_DISCOVERY,
                    &selector_discovery.to_string(),
                )
            {
                last_selector_discovery = Some(selector_discovery);
            }
            if last.as_ref() != Some(&current) {
                let control_temperature = current
                    .temperature
                    .filter(|value| value.is_finite() && (-55.0..=125.0).contains(value));
                let temperature = control_temperature
                    .map(|t| format!("{t:.2}"))
                    .unwrap_or_else(|| "None".into());
                let state = format!(
                    r#"{{"setpoint":{:.1},"delta_lower":{:.1},"delta_upper":{:.1},"cooling_wait":{},"mode":"{}","action":"{}","sensor":"{}","control_probe":"{}"}}"#,
                    current.setpoint,
                    current.lower_delta,
                    current.upper_delta,
                    current.cooling_wait,
                    if current.enabled { "auto" } else { "off" },
                    current.action,
                    probe_mqtt::control_probe_health(current.control_probe, &current.probes),
                    probe_mqtt::control_probe_state(current.control_probe)
                );
                let temp_ok = publish(&mut client, TEMP, &temperature);
                let state_ok = publish(&mut client, STATE, &state);
                let probes_ok = publish(
                    &mut client,
                    PROBES_STATE,
                    &probe_mqtt::probe_state(&current.probes).to_string(),
                );
                let probe_status_ok = publish(
                    &mut client,
                    PROBES_STATUS_STATE,
                    &probe_mqtt::probe_status_state(&current.probes).to_string(),
                );
                let diagnostics_ok = publish(
                    &mut client,
                    probe_mqtt::PROBES_DIAGNOSTICS,
                    &probe_mqtt::diagnostics_state(
                        &current.diagnostics,
                        &current.probes,
                        crate::FIRMWARE_VERSION,
                    )
                    .to_string(),
                );
                // Only the accepted, persisted selection in Snapshot is reported.
                let selection_ok = publish(
                    &mut client,
                    CONTROL_PROBE_STATE,
                    &probe_mqtt::control_probe_state(current.control_probe),
                );
                if temp_ok
                    && state_ok
                    && probes_ok
                    && probe_status_ok
                    && diagnostics_ok
                    && selection_ok
                    && publish(&mut client, AVAIL, "online")
                {
                    last = Some(current);
                }
            }
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

fn publish(client: &mut EspMqttClient<'_>, topic: &str, payload: &str) -> bool {
    match client.enqueue(topic, QoS::AtLeastOnce, true, payload.as_bytes()) {
        Ok(_) => true,
        Err(e) => {
            warn!("MQTT publish {topic}: {e}");
            false
        }
    }
}
