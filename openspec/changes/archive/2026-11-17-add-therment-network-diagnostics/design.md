# Design

## Context

See `proposal.md` for the motivation and
`specs/therment-network-diagnostics/spec.md` for the externally visible
contract. The network task already owns MQTT event processing, and the system
event loop exposes typed Wi-Fi station events. ESP-IDF's MQTT error event
contains transport and broker codes, but the current safe wrapper maps it to a
generic error, so the native event must be inspected synchronously.

## Goals / Non-Goals

**Goals:**
- Capture Wi-Fi disconnect reason/RSSI, current station signal, MQTT connection
  transitions and detailed error codes, and the startup reset reason.
- Publish a bounded boot-scoped snapshot and stable Home Assistant discovery.
- Keep callbacks short and copy only numeric evidence into the network task.

**Non-Goals:**
- Change reconnect policy, Wi-Fi power-save settings, MQTT keepalive, sampling,
  control behavior, relay interlocks, or OTA checks.
- Persist counters, collect SSID/BSSID, or add broker-side monitoring.

## Decisions

- Add a hardware-independent network-diagnostics model and serializer alongside
  the existing probe MQTT helpers. Exercise its payload and discovery contracts
  through the host-test crate; do not add a dependency.
- Subscribe to typed station-disconnect events before moving the system event
  loop into `EspWifi`. The callback copies only reason code and event RSSI into
  the existing network event queue. Read current RSSI from the associated AP
  record only while Wi-Fi reports connected; otherwise publish null.
- Register an MQTT error handler for the native error event. While the event is
  valid, copy its error type, ESP-TLS error/stack/certificate flags, socket errno
  and broker return code into a compact event. Never retain or log the native
  pointer, raw packets, credentials, or endpoint details.
- Keep all counters and latest-event data in one network-task-owned structure.
  Saturate counters, timestamp evidence with firmware uptime, and initialize the
  boot reason from `esp_reset_reason()` without writing diagnostics to NVS.
- Publish one retained JSON topic. Four stable Home Assistant sensors read RSSI,
  Wi-Fi disconnects, MQTT disconnects and MQTT errors; their shared attributes
  expose reset reason and latest event details. Do not declare the boot-scoped
  counters as long-term monotonically increasing statistics.
- Publish when state changes and at a bounded periodic interval. If MQTT is
  down, retain evidence locally and publish it after reconnection. Keep
  diagnostics publication on the existing network task so it cannot block the
  probe or control task.

## Risks / Trade-offs

- [The IDF MQTT error handle is a callback-lifetime pointer] → Check for null,
  copy only scalar fields synchronously, and never queue the pointer.
- [Wi-Fi disconnect details may be unavailable after the event] → Capture the
  reason and RSSI from the event payload; report current signal as null unless
  the station is connected and the AP record can be read.
- [Counters reset on reboot and can look discontinuous in Home Assistant] →
  Mark them boot-scoped in the payload and omit long-term counter state classes.
- [Extra retained messages increase MQTT traffic slightly] → Keep one compact
  snapshot topic, four small discovery records, and a 30-second maximum periodic
  interval; publish earlier only after a state change.

## Migration Plan

Build a uniquely versioned OTA image and use the existing deployment tool, which
validates the image slot and retains its relay/load safety checks. Before sending
the install request, obtain explicit operator confirmation that connected loads
are safe; OTA installation restarts the controller. After the update, verify the
running firmware version, retained network snapshot, Home Assistant discovery,
and continued probe/control health. If the new image fails startup, rely on the
existing rollback-enabled OTA bootloader and inspect status before attempting a
retry.