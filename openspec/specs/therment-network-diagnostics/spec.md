# Therment network diagnostics

## Purpose

Expose boot-scoped Wi-Fi, MQTT transport, and reset evidence in Home Assistant
for diagnosing intermittent Therment connectivity without serial access.

## Requirements

### Requirement: Therment publishes network diagnostics
The firmware SHALL publish a retained JSON snapshot on
`therment/therment-01/network/diagnostics` after MQTT connection, when network
diagnostic state changes and at least every 30 seconds while connected. The
snapshot SHALL include a schema version, firmware version, uptime, the ESP reset
reason for the current boot, Wi-Fi connection state and signal strength, Wi-Fi
disconnect count and latest reason code/RSSI, and MQTT connection state, connect
and disconnect counts, error count, and latest available transport or broker
error codes. Error evidence SHALL include its uptime. Counters SHALL be
saturating, boot-scoped and kept in RAM; unavailable measurements SHALL be
represented as null rather than stale values.

#### Scenario: Wi-Fi disconnects and reconnects
- **WHEN** the access point disconnects the station
- **THEN** the Wi-Fi disconnect count SHALL increase and the last disconnect
  reason code and event RSSI SHALL be recorded
- **AND** after reconnection the snapshot SHALL report current connection state
  and signal strength

#### Scenario: MQTT transport reports an error
- **WHEN** the MQTT client reports a transport or broker error
- **THEN** the error count SHALL increase and applicable ESP-TLS, socket or
  broker return codes and uptime SHALL be retained in the next snapshot
- **AND** the snapshot SHALL be republished after MQTT connectivity returns

#### Scenario: Device boots after a reset
- **WHEN** the firmware starts
- **THEN** each snapshot SHALL identify the reset reason for that boot
- **AND** boot-scoped counters SHALL start at zero without diagnostic NVS writes

### Requirement: Home Assistant discovers network diagnostics
Therment SHALL publish stable Home Assistant MQTT discovery for Wi-Fi signal,
Wi-Fi disconnects, MQTT disconnects and MQTT errors. Each entity SHALL use the
network diagnostic snapshot as its state source and expose relevant latest event
details as attributes. Boot-scoped counters SHALL NOT be represented as
monotonically increasing across device restarts.

#### Scenario: Home Assistant receives a network snapshot
- **WHEN** the device publishes network diagnostics
- **THEN** the discovered entities SHALL show current Wi-Fi signal and the
  current-boot disconnect/error counts
- **AND** their attributes SHALL expose reset reason and latest Wi-Fi/MQTT error
  details

#### Scenario: Wi-Fi signal is unavailable
- **WHEN** the station is not connected or signal strength cannot be read
- **THEN** the snapshot's signal value SHALL be null and the entity SHALL NOT
  present an earlier RSSI reading as current

### Requirement: Network diagnostics do not affect control or disclose secrets
Network diagnostic collection and publication SHALL NOT change probe sampling,
thermostat decisions, relay behavior, persisted settings or OTA safety checks.
Snapshots and logs SHALL NOT include Wi-Fi SSID, BSSID, passwords, MQTT
credentials or OTA tokens. Lack of Wi-Fi or MQTT connectivity SHALL NOT block
autonomous temperature regulation.

#### Scenario: Network is unavailable during autonomous operation
- **WHEN** Wi-Fi or MQTT is disconnected
- **THEN** probe sampling and local thermostat control SHALL continue unchanged
- **AND** diagnostic state SHALL be published when connectivity is restored