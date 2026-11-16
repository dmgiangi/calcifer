# Proposal

## Why

Therment firmware currently requires USB for every update. Provide a simple, observable OTA path that keeps local temperature regulation safe and lets both Home Assistant and the development agent follow an update.

## What Changes

- Deploy hawkBit only on `calcifer-home`, with private LAN HTTPS and persistent `local-path` storage.
- Build on a trusted workstation and upload, assign, trigger, and monitor releases with a script using SOPS-encrypted credentials.
- Add automatic polling, an on-demand MQTT Update command, safe inactive-slot installation, startup confirmation, and rollback to Therment.
- Report version, progress, availability, and final outcomes to Home Assistant and to a machine-readable operator interface.
- Document and verify first-time USB migration, recovery, and protection of firmware images containing credentials.

## Capabilities

### New Capabilities

- `therment-ota`: Private publication, automatic and manual OTA installation, safe recovery, and observable status for Therment.

### Modified Capabilities

None.

## Impact

Therment Rust firmware and partition configuration; Home-only Kubernetes manifests, SOPS credentials, and hawkBit storage; local deployment tooling and MQTT discovery; operating documentation. The existing Home MQTT broker and clients must continue working. No Cloud deployment is required.
