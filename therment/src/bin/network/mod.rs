use crate::{Command, Snapshot, mqtt_credentials, wifi_credentials};
use esp_idf_svc::eventloop::EspSystemEventLoop;
use esp_idf_svc::hal::modem::Modem;
use esp_idf_svc::mqtt::client::{
    Details, EspMqttClient, EventPayload, LwtConfiguration, MqttClientConfiguration, QoS,
};
use esp_idf_svc::nvs::EspDefaultNvsPartition;
use esp_idf_svc::wifi::{ClientConfiguration, Configuration, EspWifi};
use log::{info, warn};
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

enum Event {
    Connected,
    Disconnected,
    Received(String, Vec<u8>),
}

pub fn run(
    modem: Modem,
    sys: EspSystemEventLoop,
    nvs: EspDefaultNvsPartition,
    commands: mpsc::Sender<Command>,
    snapshot: Arc<Mutex<Snapshot>>,
) -> Result<(), Box<dyn std::error::Error>> {
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
    let url = format!(
        "mqtt://{}:{}",
        mqtt_credentials::MQTT_HOST,
        mqtt_credentials::MQTT_PORT
    );
    let (events_tx, events_rx) = mpsc::channel();
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
    let mut client = loop {
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
                } if data.len() <= 64 => Some(Event::Received(topic.to_owned(), data.to_vec())),
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
    let mut connected = false;
    let mut subscribed = false;
    let mut last_wifi_attempt = Instant::now() - Duration::from_secs(5);
    let mut last = None;
    loop {
        if !wifi.is_connected().unwrap_or(false)
            && last_wifi_attempt.elapsed() >= Duration::from_secs(5)
        {
            last_wifi_attempt = Instant::now();
            if let Err(error) = wifi.connect() {
                warn!("Wi-Fi connection attempt: {error}");
            }
        }
        while let Ok(event) = events_rx.try_recv() {
            match event {
                Event::Connected => {
                    info!("MQTT connected");
                    connected = true;
                    subscribed = false;
                    last = None;
                }
                Event::Disconnected => {
                    info!("MQTT disconnected; automatic reconnect enabled");
                    connected = false;
                    subscribed = false;
                    last = None;
                }
                Event::Received(topic, data) => {
                    let value = String::from_utf8_lossy(&data).into_owned();
                    if topic == SETPOINT {
                        let _ = commands.send(Command::Setpoint(value));
                    } else if topic == LOWER || topic == UPPER {
                        let _ = commands.send(Command::Delta(topic == UPPER, value));
                    } else if topic == MODE {
                        let _ = commands.send(Command::Mode(value));
                    }
                    last = None;
                }
            }
        }
        if connected && !subscribed {
            let setpoint_ok = client.subscribe(SETPOINT, QoS::AtLeastOnce).is_ok();
            let mode_ok = client.subscribe(MODE, QoS::AtLeastOnce).is_ok();
            let lower_ok = client.subscribe(LOWER, QoS::AtLeastOnce).is_ok();
            let upper_ok = client.subscribe(UPPER, QoS::AtLeastOnce).is_ok();
            if setpoint_ok
                && mode_ok
                && lower_ok
                && upper_ok
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
            {
                subscribed = true;
                last = None;
            } else {
                warn!("MQTT setup failed; retrying");
            }
        }
        if connected && subscribed {
            let current = *snapshot.lock().unwrap();
            if last != Some(current) {
                let temperature = current
                    .temperature
                    .map(|t| format!("{t:.2}"))
                    .unwrap_or_else(|| "None".into());
                let state = format!(
                    r#"{{"setpoint":{:.1},"delta_lower":{:.1},"delta_upper":{:.1},"cooling_wait":{},"mode":"{}","action":"{}","sensor":"{}"}}"#,
                    current.setpoint,
                    current.lower_delta,
                    current.upper_delta,
                    current.cooling_wait,
                    if current.enabled { "auto" } else { "off" },
                    current.action,
                    if current.temperature.is_some() {
                        "ok"
                    } else {
                        "error"
                    }
                );
                let temp_ok = publish(&mut client, TEMP, &temperature);
                let state_ok = publish(&mut client, STATE, &state);
                if temp_ok && state_ok && publish(&mut client, AVAIL, "online") {
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
