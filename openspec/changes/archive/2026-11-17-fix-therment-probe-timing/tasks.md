# Tasks

## 1. Firmware

- [x] 1.1 Extract protected GPIO timing; verify host trace tests cover pulse, sampling, recovery and error paths.
- [x] 1.2 Categorize sampling failures and accumulate bounded boot diagnostics; verify injected failures and recovery with host tests.
- [x] 1.3 Publish diagnostic MQTT data and HA discovery; verify payloads, identities and unchanged legacy payloads in host tests.

## 2. Validation and release

- [x] 2.1 Document telemetry semantics and hardware limits; run formatting, host regressions and OpenSpec validation. Rust 35/35 and OTA Python 8/8 pass; the separate backup-test import is blocked by a pre-existing missing scripts/hawkbit-local-backup.py.
- [x] 2.2 Build the OTA profile and verify compilation and image slot fit without exposing firmware or credentials. Release probe-timing-diagnostics-1 builds successfully: 1,322,144 bytes / 1,966,080-byte slot.
- [x] 2.3 After load-safety confirmation, deploy a unique release and verify matching installed version and multiple live diagnostic samples. Operator authorized OTA; action 21 finished successfully with probe-timing-diagnostics-1. Ten consecutive live diagnostic updates verified; at uptime 191 s, both probes had 13 successes, zero failures and ok health, with all bus event counters zero. Three retained HA diagnostic discovery configurations verified over MQTT; longer-term reliability and HA UI acceptance remain unverified.