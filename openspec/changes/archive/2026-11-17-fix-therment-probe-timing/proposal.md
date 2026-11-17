# Proposal

## Why

Intermittent probe failures may be caused by interruptible OneWire bit timing.
Existing health states cannot distinguish bus, conversion and data-integrity failures.

## What Changes

- Protect timing-sensitive GPIO sequences and sample read bits with timing margin.
- Publish retained, boot-scoped bus counters and per-ROM failure details over MQTT,
  with Home Assistant diagnostic entities.
- Preserve sampling interval, bounded bus work, control selection and fault safety.
- Test on the host, build the OTA image and release after load-safety confirmation.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `therment-probe-selection`: reliable timing and categorized read diagnostics.

## Impact

Only Therment firmware, host tests and operator documentation change. No new
dependencies, credentials, cluster workloads or MQTT ACL permissions are required.