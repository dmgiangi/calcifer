//! Hardware-independent Home Assistant discovery and current probe state.

use crate::probes::{MonitoredProbe, Rom};
use serde_json::{Value, json};
use std::collections::BTreeSet;

pub const CONTROL_PROBE_SET: &str = "therment/therment-01/control_probe/set";
pub const CONTROL_PROBE_STATE: &str = "therment/therment-01/control_probe/state";
pub const CONTROL_PROBE_DISCOVERY: &str = "homeassistant/select/therment-01-control-probe/config";
pub const PROBES_STATE: &str = "therment/therment-01/probes/state";
pub const PROBES_STATUS_STATE: &str = "therment/therment-01/probes/status";
const AVAILABILITY: &str = "therment/therment-01/availability";

pub fn control_probe_state(selected: Option<Rom>) -> String {
    selected.map_or_else(|| "None".into(), |rom| rom.to_string())
}

pub fn control_probe_discovery(probes: &[MonitoredProbe], selected: Option<Rom>) -> Value {
    let roms: BTreeSet<_> = probes
        .iter()
        .filter(|probe| probe.detected)
        .map(|probe| probe.rom)
        .chain(selected)
        .collect();
    let options: Vec<_> = std::iter::once("None".to_owned())
        .chain(roms.into_iter().map(|rom| rom.to_string()))
        .collect();
    json!({
        "name": "Control probe",
        "unique_id": "therment-01-control-probe",
        "device": {"identifiers": ["therment-01"]},
        "entity_category": "config",
        "command_topic": CONTROL_PROBE_SET,
        "state_topic": CONTROL_PROBE_STATE,
        "availability_topic": AVAILABILITY,
        "options": options,
        "qos": 1,
        "retain": false,
        "optimistic": false,
    })
}

pub fn probe_discovery_topic(rom: Rom) -> String {
    format!("homeassistant/sensor/therment-01-probe-{rom}-temperature/config")
}

pub fn probe_discovery(rom: Rom) -> Value {
    json!({
        "name": format!("Probe {rom} temperature"),
        "unique_id": format!("therment-01-probe-{rom}-temperature"),
        "device": {"identifiers": ["therment-01"]},
        "device_class": "temperature",
        "state_class": "measurement",
        "unit_of_measurement": "°C",
        "state_topic": PROBES_STATE,
        "value_template": format!("{{{{ value_json.get('{rom}') | default(none) }}}}"),
        "availability_mode": "all",
        "availability": [
            {"topic": AVAILABILITY},
            {
                "topic": PROBES_STATE,
                "value_template": format!("{{{{ 'online' if value_json.get('{rom}') is number else 'offline' }}}}"),
            },
        ],
        "qos": 1,
    })
}

pub fn probe_status_discovery_topic(rom: Rom) -> String {
    format!("homeassistant/sensor/therment-01-probe-{rom}-status/config")
}

pub fn probe_status_discovery(rom: Rom) -> Value {
    json!({
        "name": format!("Probe {rom} status"),
        "unique_id": format!("therment-01-probe-{rom}-status"),
        "device": {"identifiers": ["therment-01"]},
        "entity_category": "diagnostic",
        "state_topic": PROBES_STATUS_STATE,
        "value_template": format!("{{{{ value_json.get('{rom}') | default('unknown') }}}}"),
        "availability_topic": AVAILABILITY,
        "qos": 1,
    })
}

/// Replace the whole retained map: absent ROMs must not keep an old reading.
pub fn probe_state(probes: &[MonitoredProbe]) -> Value {
    Value::Object(
        probes
            .iter()
            .map(|probe| {
                let temperature = probe
                    .temperature
                    .filter(|value| value.is_finite() && (-55.0..=125.0).contains(value));
                (probe.rom.to_string(), json!(temperature))
            })
            .collect(),
    )
}

pub fn probe_status_state(probes: &[MonitoredProbe]) -> Value {
    Value::Object(
        probes
            .iter()
            .map(|probe| (probe.rom.to_string(), json!(probe.health.as_str())))
            .collect(),
    )
}

pub fn control_probe_health(selected: Option<Rom>, probes: &[MonitoredProbe]) -> &'static str {
    selected
        .and_then(|rom| probes.iter().find(|probe| probe.rom == rom))
        .map_or("error", |probe| probe.health.as_str())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::probes::ProbeHealth;

    fn probe(id: u8, temperature: Option<f32>) -> MonitoredProbe {
        MonitoredProbe {
            rom: Rom([0x28, id, 0xab, 0, 0, 0, 0, 0xcd]),
            temperature,
            consecutive_failures: 0,
            health: if temperature.is_some() {
                ProbeHealth::Ok
            } else {
                ProbeHealth::Stale
            },
            detected: true,
        }
    }

    #[test]
    fn selector_is_non_optimistic_config_with_sorted_canonical_options() {
        let first = probe(1, None);
        let second = probe(2, Some(20.0));
        let config = control_probe_discovery(&[second, first, second], Some(second.rom));
        assert_eq!(config["name"], "Control probe");
        assert_eq!(config["unique_id"], "therment-01-control-probe");
        assert_eq!(
            CONTROL_PROBE_DISCOVERY,
            "homeassistant/select/therment-01-control-probe/config"
        );
        assert_eq!(config["entity_category"], "config");
        assert_eq!(config["device"]["identifiers"], json!(["therment-01"]));
        assert_eq!(config["command_topic"], CONTROL_PROBE_SET);
        assert_eq!(config["state_topic"], CONTROL_PROBE_STATE);
        assert_eq!(config["availability_topic"], AVAILABILITY);
        assert_eq!(config["qos"], 1);
        assert_eq!(config["retain"], false);
        assert_eq!(config["optimistic"], false);
        assert_eq!(
            config["options"],
            json!(["None", "2801ab00000000cd", "2802ab00000000cd"])
        );
        assert_eq!(control_probe_state(Some(first.rom)), "2801ab00000000cd");
        // A new temperature does not change discovery.
        assert_eq!(
            config,
            control_probe_discovery(&[probe(2, None), probe(1, Some(30.0))], Some(second.rom))
        );
    }

    #[test]
    fn selector_keeps_saved_selection_even_without_detected_probes() {
        let selected = probe(2, None).rom;
        let expected = json!(["None", selected.to_string()]);
        assert_eq!(
            control_probe_discovery(&[], Some(selected))["options"],
            expected
        );
        assert_eq!(
            control_probe_discovery(&[], None)["options"],
            json!(["None"])
        );
        assert_eq!(control_probe_state(None), "None");
        assert_eq!(
            control_probe_discovery(&[probe(1, None)], Some(selected))["options"],
            json!(["None", probe(1, None).rom.to_string(), selected.to_string()])
        );
    }

    #[test]
    fn shared_state_contains_only_current_valid_readings_or_null() {
        let probes = [
            probe(1, Some(21.25)),
            probe(2, None),
            probe(3, Some(f32::NAN)),
            probe(4, Some(f32::INFINITY)),
            probe(5, Some(f32::NEG_INFINITY)),
            probe(6, Some(125.1)),
            probe(7, Some(-55.1)),
            probe(8, Some(-55.0)),
            probe(9, Some(125.0)),
        ];
        let state = probe_state(&probes);
        assert_eq!(state.as_object().unwrap().len(), probes.len());
        assert_eq!(state[probes[0].rom.to_string()], 21.25);
        for probe in &probes[1..7] {
            assert!(state[probe.rom.to_string()].is_null());
        }
        assert_eq!(state[probes[7].rom.to_string()], -55.0);
        assert_eq!(state[probes[8].rom.to_string()], 125.0);
        let removed = probe_state(&probes[1..]);
        assert!(removed.get(probes[0].rom.to_string()).is_none());
        assert_eq!(probe_state(&[]), json!({}));
        let encoded = serde_json::to_string(&state).unwrap();
        assert_eq!(serde_json::from_str::<Value>(&encoded).unwrap(), state);
    }

    #[test]
    fn sensor_discovery_uses_stable_rom_identity_and_missing_safe_templates() {
        let rom = probe(1, None).rom;
        let config = probe_discovery(rom);
        assert_eq!(
            probe_discovery_topic(rom),
            "homeassistant/sensor/therment-01-probe-2801ab00000000cd-temperature/config"
        );
        assert_eq!(
            config["unique_id"],
            "therment-01-probe-2801ab00000000cd-temperature"
        );
        assert_ne!(
            config["unique_id"],
            probe_discovery(probe(2, None).rom)["unique_id"]
        );
        assert_eq!(config["device_class"], "temperature");
        assert_eq!(config["unit_of_measurement"], "°C");
        assert_eq!(config["state_topic"], PROBES_STATE);
        assert_eq!(
            config["value_template"],
            "{{ value_json.get('2801ab00000000cd') | default(none) }}"
        );
        assert_eq!(config["availability_mode"], "all");
        assert_eq!(config["availability"][0]["topic"], AVAILABILITY);
        assert_eq!(config["availability"][1]["topic"], PROBES_STATE);
        assert_eq!(
            config["availability"][1]["value_template"],
            "{{ 'online' if value_json.get('2801ab00000000cd') is number else 'offline' }}"
        );
        // Unknown retained discoveries also read this map: no key means unavailable.
        assert!(probe_state(&[]).get(rom.to_string()).is_none());
        assert!(probe_state(&[probe(1, None)])[rom.to_string()].is_null());
    }

    #[test]
    fn status_entity_exposes_stale_and_fault_without_hiding_probe_health() {
        let rom = probe(1, Some(20.0)).rom;
        let mut stale = probe(1, Some(20.0));
        stale.health = ProbeHealth::Stale;
        stale.consecutive_failures = 4;
        let mut fault = probe(2, None);
        fault.health = ProbeHealth::Fault;
        fault.consecutive_failures = 5;
        let probes = [stale, fault];
        assert_eq!(probe_status_state(&probes)[rom.to_string()], "stale");
        assert_eq!(probe_status_state(&probes)[fault.rom.to_string()], "fault");
        assert_eq!(control_probe_health(Some(rom), &probes), "stale");
        assert_eq!(control_probe_health(None, &probes), "error");

        let config = probe_status_discovery(rom);
        assert_eq!(
            probe_status_discovery_topic(rom),
            "homeassistant/sensor/therment-01-probe-2801ab00000000cd-status/config"
        );
        assert_eq!(config["entity_category"], "diagnostic");
        assert_eq!(config["state_topic"], PROBES_STATUS_STATE);
    }
}
