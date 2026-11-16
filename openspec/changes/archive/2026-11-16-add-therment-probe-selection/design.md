# Design

## Context

GPIO13 hosts two externally powered DS18B20 probes. GPIO17 is heating and GPIO5
is cooling; both relays are active-low. A single compressor serves chamber and
freezer. The existing OTA acceptance task remains separate and incomplete.

## Decisions

- Search ROM with Dallas CRC and family validation; visit at most eight ROM
  paths per cycle, including unsupported devices, to bound control-loop work.
- Broadcast conversion once, wait 750 ms, check completion, then use Match ROM
  for each scratchpad. Reject invalid CRCs and DS18B20 physical-range values.
- Track each probe independently. A valid reading resets its consecutive-failure
  count; the last valid value is retained and marked stale for failures one to
  four. Failure five clears that value and faults the probe; the first later
  valid reading restores it. A missing device and a bus I/O error are failures
  for each previously tracked probe. History is bounded to the current bus
  inventory plus the failure-debounce window.
- Use canonical 16-character lowercase hex ROM IDs in bus byte order. Store
  the selected ROM as little-endian NVS u64 `control_probe`; zero means none.
  Persist before accepting/publishing a changed association.
- Without a valid stored selection, boot disabled, including migration from
  single-probe firmware. Never infer roles from bus order or temperature.
- Accept only `None` or a detected valid DS18B20 ROM with control disabled and
  both outputs inactive. Changing selection invalidates the control sample and
  restarts the five-minute compressor guard; enabling requires a separate mode
  command. Missing/failed selected probes inhibit outputs without fallback.
- Reject replayed retained selection commands using an ESP-IDF native DATA callback
  because the current embedded-svc MQTT event omits the retain flag. Keep its
  boxed sender alive until after MQTT client destruction. MQTT 3.1.1 does not
  mark live deliveries from retained publishes; operators must use retain=false.
- Publish retained selection state only from the accepted local setting.
  Include a missing saved ROM in selector options so it remains visible.
- Publish per-probe `ok`, `stale` or `fault` diagnostics in MQTT/Home Assistant.
  The numeric entity continues to show the last good value only during the
  four-sample grace period; it becomes unavailable on the fifth failure.
- Treat -15 °C and +40 °C as directional operating cutouts, not sensor limits:
  do not cool below -15 °C or heat above +40 °C. Do not clamp valid readings;
  the opposite direction remains available to recover toward the setpoint.
- Keep stable per-ROM temperature discovery and replace one shared retained
  JSON readings map. Missing/null entries make that probe unavailable alongside
  device availability. Clear previous readings before publishing online after
  reconnect. Retain state/discovery, never selection commands.

## Verification and rollout

Host tests model colliding devices, addressed reads, failed conversions/CRC,
debounced probe history, directional temperature cutouts, selection policy,
persistent ID encoding and MQTT payloads. Build the private
OTA profile and check slot fit without publishing. Installation and physical
acceptance require the operator's safe-load maintenance confirmation. Do not
mark NVS durability or relay timing physically verified based on host tests.