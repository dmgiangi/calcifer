# Proposal

## Why

The physical refrigerator has chamber and freezer DS18B20 probes on one bus,
but the firmware assumes one sensor. Only the chamber may control the shared
compressor. Operators need to choose that probe without rebuilding firmware.

## What Changes

- Enumerate and individually read DS18B20 probes by validated ROM address.
- Publish a temperature entity per probe and an MQTT/Home Assistant selector.
- Persist the explicit control-probe association locally; never auto-select.
- Require disabled control and inactive outputs before changing the association.
- Retain the last valid temperature for four consecutive failed samples; fault
  the probe on the fifth and inhibit both outputs until a valid sample returns.
- Keep measured temperatures unchanged while preventing cooling below -15 °C and
  heating above +40 °C.

## Capabilities

### New Capabilities

- `therment-probe-selection`: Persistent MQTT-configurable control-probe mapping
  with independent read-only probe monitoring.

### Modified Capabilities

None.

## Impact

Therment firmware, host tests and operating documentation only. Reuse the
existing private OTA release path. No Kubernetes or credential changes.