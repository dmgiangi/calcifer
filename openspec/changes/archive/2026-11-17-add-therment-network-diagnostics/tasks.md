# Tasks

## 1. Network diagnostic model

- [x] 1.1 Add a hardware-independent boot-scoped model and JSON serializer for Wi-Fi, MQTT, reset and error evidence; verify saturation, null handling and payload fields with host unit tests.
- [x] 1.2 Add stable Home Assistant discovery for Wi-Fi RSSI and Wi-Fi/MQTT counters; verify identifiers, templates, attributes and boot-scoped counter metadata with host tests.

## 2. Firmware event collection and publication

- [x] 2.1 Capture Wi-Fi disconnect reason/RSSI, MQTT transitions and scalar MQTT transport/broker errors, plus startup reset reason; verify the OTA firmware compiles without changing control or probe logic.
- [x] 2.2 Publish retained network diagnostics and discovery on connection/state change and periodically; verify reconnect snapshots and that the JSON contains no credentials or network identifiers.

## 3. Validation and release preparation

- [x] 3.1 Run host tests, Python OTA tests, formatting, OpenSpec validation and the OTA image-size check; verify all pass and the image fits its slot.

## 4. Authorized installation

- [x] 4.1 After explicit confirmation that connected loads are safe, deploy a unique OTA version; verify the action succeeds and the installed firmware version matches.
- [x] 4.2 Verify retained network diagnostics and Home Assistant entities after reboot, and confirm probe readings and selected control probe remain healthy.