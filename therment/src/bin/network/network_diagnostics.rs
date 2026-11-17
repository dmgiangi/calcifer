//! Hardware-independent, boot-scoped Wi-Fi and MQTT diagnostics.

use serde_json::{Value, json};

pub const STATE_TOPIC: &str = "therment/therment-01/network/diagnostics";
const AVAILABILITY_TOPIC: &str = "therment/therment-01/availability";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MqttError {
    pub error_type_code: u32,
    pub error_type: &'static str,
    pub esp_tls_last_error: Option<i32>,
    pub tls_stack_error: Option<i32>,
    pub tls_certificate_flags: Option<i32>,
    pub socket_errno: Option<i32>,
    pub broker_return_code: Option<u32>,
}

impl MqttError {
    pub fn from_raw(
        error_type_code: u32,
        esp_tls_last_error: i32,
        tls_stack_error: i32,
        tls_certificate_flags: i32,
        socket_errno: i32,
        broker_return_code: u32,
    ) -> Self {
        let tcp = error_type_code == 1;
        let refused = error_type_code == 2;
        Self {
            error_type_code,
            error_type: match error_type_code {
                0 => "none",
                1 => "tcp_transport",
                2 => "connection_refused",
                3 => "subscribe_failed",
                _ => "unknown",
            },
            esp_tls_last_error: tcp.then_some(esp_tls_last_error),
            tls_stack_error: tcp.then_some(tls_stack_error),
            tls_certificate_flags: tcp.then_some(tls_certificate_flags),
            socket_errno: tcp.then_some(socket_errno),
            broker_return_code: refused.then_some(broker_return_code),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct WifiDisconnect {
    reason_code: u16,
    rssi_dbm: i8,
    uptime_s: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct LastMqttError {
    error: MqttError,
    uptime_s: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NetworkDiagnostics {
    reset_reason_code: u32,
    wifi_disconnects: u64,
    mqtt_connected: bool,
    mqtt_connects: u64,
    mqtt_disconnects: u64,
    mqtt_errors: u64,
    last_wifi_disconnect: Option<WifiDisconnect>,
    last_mqtt_error: Option<LastMqttError>,
}

impl NetworkDiagnostics {
    pub fn new(reset_reason_code: u32) -> Self {
        Self {
            reset_reason_code,
            wifi_disconnects: 0,
            mqtt_connected: false,
            mqtt_connects: 0,
            mqtt_disconnects: 0,
            mqtt_errors: 0,
            last_wifi_disconnect: None,
            last_mqtt_error: None,
        }
    }

    pub fn record_wifi_disconnect(&mut self, reason_code: u16, rssi_dbm: i8, uptime_s: u64) {
        self.wifi_disconnects = self.wifi_disconnects.saturating_add(1);
        self.last_wifi_disconnect = Some(WifiDisconnect {
            reason_code,
            rssi_dbm,
            uptime_s,
        });
    }

    pub fn record_mqtt_connected(&mut self) {
        self.mqtt_connected = true;
        self.mqtt_connects = self.mqtt_connects.saturating_add(1);
    }

    pub fn record_mqtt_disconnected(&mut self) {
        self.mqtt_connected = false;
        self.mqtt_disconnects = self.mqtt_disconnects.saturating_add(1);
    }

    pub fn record_mqtt_error(&mut self, error: MqttError, uptime_s: u64) {
        self.mqtt_errors = self.mqtt_errors.saturating_add(1);
        self.last_mqtt_error = Some(LastMqttError { error, uptime_s });
    }

    pub fn state(
        &self,
        firmware_version: &str,
        uptime_s: u64,
        wifi_connected: bool,
        wifi_rssi_dbm: Option<i32>,
    ) -> Value {
        let wifi_rssi_dbm = wifi_connected.then_some(wifi_rssi_dbm).flatten();
        let last_wifi_disconnect = self.last_wifi_disconnect.map(|event| {
            json!({
                "reason_code": event.reason_code,
                "rssi_dbm": event.rssi_dbm,
                "uptime_s": event.uptime_s,
            })
        });
        let last_mqtt_error = self.last_mqtt_error.map(|event| {
            json!({
                "type": event.error.error_type,
                "type_code": event.error.error_type_code,
                "esp_tls_last_error": event.error.esp_tls_last_error,
                "tls_stack_error": event.error.tls_stack_error,
                "tls_certificate_flags": event.error.tls_certificate_flags,
                "socket_errno": event.error.socket_errno,
                "broker_return_code": event.error.broker_return_code,
                "uptime_s": event.uptime_s,
            })
        });

        json!({
            "schema_version": 1,
            "firmware_version": firmware_version,
            "counter_scope": "boot",
            "uptime_s": uptime_s,
            "reset_reason": {
                "code": self.reset_reason_code,
                "name": reset_reason_name(self.reset_reason_code),
            },
            "wifi": {
                "connected": wifi_connected,
                "rssi_dbm": wifi_rssi_dbm,
                "disconnects": self.wifi_disconnects,
                "last_disconnect": last_wifi_disconnect,
            },
            "mqtt": {
                "connected": self.mqtt_connected,
                "connects": self.mqtt_connects,
                "disconnects": self.mqtt_disconnects,
                "errors": self.mqtt_errors,
                "last_error": last_mqtt_error,
            },
        })
    }
}

pub fn reset_reason_name(code: u32) -> &'static str {
    match code {
        0 => "unknown",
        1 => "power_on",
        2 => "external",
        3 => "software",
        4 => "panic",
        5 => "interrupt_watchdog",
        6 => "task_watchdog",
        7 => "watchdog",
        8 => "deep_sleep",
        9 => "brownout",
        10 => "sdio",
        11 => "usb",
        12 => "jtag",
        13 => "efuse",
        14 => "power_glitch",
        15 => "cpu_lockup",
        _ => "other",
    }
}

pub fn discovery_configs() -> [(&'static str, Value); 4] {
    let attributes = "{{ value_json | tojson }}";
    [
        (
            "homeassistant/sensor/therment-01-network-wifi-rssi/config",
            json!({
                "name": "Therment Wi-Fi signal",
                "unique_id": "therment-01-network-wifi-rssi",
                "device": {"identifiers": ["therment-01"]},
                "entity_category": "diagnostic",
                "device_class": "signal_strength",
                "state_class": "measurement",
                "unit_of_measurement": "dBm",
                "state_topic": STATE_TOPIC,
                "value_template": "{{ value_json.wifi.rssi_dbm }}",
                "json_attributes_topic": STATE_TOPIC,
                "json_attributes_template": attributes,
                "availability_topic": AVAILABILITY_TOPIC,
                "qos": 1,
            }),
        ),
        (
            "homeassistant/sensor/therment-01-network-wifi-disconnects/config",
            counter_discovery(
                "Therment Wi-Fi disconnects",
                "therment-01-network-wifi-disconnects",
                "{{ value_json.wifi.disconnects }}",
                attributes,
            ),
        ),
        (
            "homeassistant/sensor/therment-01-network-mqtt-disconnects/config",
            counter_discovery(
                "Therment MQTT disconnects",
                "therment-01-network-mqtt-disconnects",
                "{{ value_json.mqtt.disconnects }}",
                attributes,
            ),
        ),
        (
            "homeassistant/sensor/therment-01-network-mqtt-errors/config",
            counter_discovery(
                "Therment MQTT errors",
                "therment-01-network-mqtt-errors",
                "{{ value_json.mqtt.errors }}",
                attributes,
            ),
        ),
    ]
}

fn counter_discovery(
    name: &str,
    unique_id: &str,
    value_template: &str,
    attributes_template: &str,
) -> Value {
    json!({
        "name": name,
        "unique_id": unique_id,
        "device": {"identifiers": ["therment-01"]},
        "entity_category": "diagnostic",
        "state_topic": STATE_TOPIC,
        "value_template": value_template,
        "json_attributes_topic": STATE_TOPIC,
        "json_attributes_template": attributes_template,
        "availability_topic": AVAILABILITY_TOPIC,
        "qos": 1,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_reports_boot_reason_link_state_and_numeric_error_evidence() {
        let mut diagnostics = NetworkDiagnostics::new(9);
        diagnostics.record_wifi_disconnect(201, -71, 23);
        diagnostics.record_mqtt_connected();
        diagnostics.record_mqtt_disconnected();
        diagnostics.record_mqtt_error(MqttError::from_raw(1, -1, -0x1234, 4, 104, 0), 28);

        let state = diagnostics.state("test-release", 30, false, Some(-52));
        assert_eq!(state["schema_version"], 1);
        assert_eq!(state["firmware_version"], "test-release");
        assert_eq!(state["counter_scope"], "boot");
        assert_eq!(
            state["reset_reason"],
            json!({"code": 9, "name": "brownout"})
        );
        assert_eq!(state["wifi"]["connected"], false);
        assert!(state["wifi"]["rssi_dbm"].is_null());
        assert_eq!(state["wifi"]["disconnects"], 1);
        assert_eq!(state["wifi"]["last_disconnect"]["reason_code"], 201);
        assert_eq!(state["wifi"]["last_disconnect"]["rssi_dbm"], -71);
        assert_eq!(state["mqtt"]["connects"], 1);
        assert_eq!(state["mqtt"]["disconnects"], 1);
        assert_eq!(state["mqtt"]["errors"], 1);
        assert_eq!(state["mqtt"]["last_error"]["type"], "tcp_transport");
        assert_eq!(state["mqtt"]["last_error"]["socket_errno"], 104);
        assert_eq!(state["mqtt"]["last_error"]["uptime_s"], 28);

        let serialized = state.to_string();
        for private_field in ["ssid", "bssid", "password", "username", "token"] {
            assert!(!serialized.contains(private_field));
        }
    }

    #[test]
    fn counters_saturate_and_restart_with_a_new_boot_snapshot() {
        let mut diagnostics = NetworkDiagnostics::new(1);
        diagnostics.wifi_disconnects = u64::MAX;
        diagnostics.mqtt_connects = u64::MAX;
        diagnostics.mqtt_disconnects = u64::MAX;
        diagnostics.mqtt_errors = u64::MAX;
        diagnostics.record_wifi_disconnect(1, -80, 10);
        diagnostics.record_mqtt_connected();
        diagnostics.record_mqtt_disconnected();
        diagnostics.record_mqtt_error(MqttError::from_raw(0, 0, 0, 0, 0, 0), 10);
        assert_eq!(diagnostics.wifi_disconnects, u64::MAX);
        assert_eq!(diagnostics.mqtt_connects, u64::MAX);
        assert_eq!(diagnostics.mqtt_disconnects, u64::MAX);
        assert_eq!(diagnostics.mqtt_errors, u64::MAX);

        let after_restart = NetworkDiagnostics::new(3);
        let state = after_restart.state("release", 1, true, Some(-50));
        assert_eq!(state["counter_scope"], "boot");
        assert_eq!(state["mqtt"]["connects"], 0);
        assert_eq!(state["wifi"]["disconnects"], 0);
        assert_eq!(state["wifi"]["rssi_dbm"], -50);
    }

    #[test]
    fn discovery_has_stable_ids_and_boot_counters_are_not_long_term_statistics() {
        let configs = discovery_configs();
        assert_eq!(configs.len(), 4);
        assert_eq!(configs[0].1["unique_id"], "therment-01-network-wifi-rssi");
        assert_eq!(configs[0].1["state_class"], "measurement");
        for (_, config) in &configs[1..] {
            assert_eq!(config["entity_category"], "diagnostic");
            assert!(config.get("state_class").is_none());
        }
        for (_, config) in &configs {
            assert_eq!(config["state_topic"], STATE_TOPIC);
            assert_eq!(config["json_attributes_topic"], STATE_TOPIC);
            assert_eq!(config["device"]["identifiers"], json!(["therment-01"]));
        }
    }

    #[test]
    fn reset_reason_names_cover_known_reasons_and_unknown_values() {
        assert_eq!(reset_reason_name(4), "panic");
        assert_eq!(reset_reason_name(9), "brownout");
        assert_eq!(reset_reason_name(15), "cpu_lockup");
        assert_eq!(reset_reason_name(99), "other");
    }
}
