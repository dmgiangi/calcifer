# Proposal

## Why

Therment's probe diagnostics show successful sampling, but MQTT broker records
show intermittent disconnects without identifying whether Wi-Fi or the MQTT/TCP
transport caused them. Boot-scoped network evidence will let a later occurrence
be diagnosed from Home Assistant without a serial connection.

## What Changes

- Publish a retained, bounded network diagnostic snapshot with the boot reset
  reason, Wi-Fi state and signal, Wi-Fi disconnect causes, MQTT connection
  counts, and detailed MQTT transport/broker error codes.
- Add Home Assistant diagnostic entities for Wi-Fi signal and Wi-Fi/MQTT event
  counters, with the latest failure details exposed as attributes.
- Keep network diagnostics in RAM for the current boot; do not write diagnostic
  data to NVS or change probe sampling, control policy, relay behavior, or OTA
  safety checks.

## Capabilities

### New Capabilities
- `therment-network-diagnostics`: Boot-scoped Wi-Fi, MQTT and reset diagnostics
  published over MQTT and discoverable in Home Assistant.

### Modified Capabilities

None.

## Impact

- Firmware network event handling and Home Assistant MQTT discovery under
  `therment/src/bin/network/`.
- Hardware-independent payload/discovery tests in the OTA policy host-test
  crate.
- No new dependencies, broker configuration changes, or changes to the
  `calcifer-cloud` cluster.