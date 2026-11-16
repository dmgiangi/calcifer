# therment-ota Specification

## Purpose
Provide safe, private firmware updates for Therment with clear status for Home Assistant and operators.

## Requirements

### Requirement: Releases are published privately
The system SHALL allow a trusted workstation to build and publish an identifiable Therment firmware release to an authenticated Home-only service. Firmware images and credentials SHALL NOT be committed or logged; upload credentials SHALL be encrypted with SOPS in Git. The service SHALL persist its release metadata and artifacts across restarts and preserve current Home MQTT clients.

#### Scenario: Publish an assigned release
- **WHEN** an authorized operator publishes an image and assigns it to Therment
- **THEN** the release SHALL be available to that device with its version, size, and checksum
- **AND** a duplicate version with different image content SHALL be rejected

#### Scenario: Unauthorized upload or download
- **WHEN** an unauthenticated or unauthorized client attempts to publish or retrieve an image
- **THEN** the service SHALL reject the request

### Requirement: Therment installs updates safely
Therment SHALL check for assigned releases automatically and on demand, use authenticated HTTPS with certificate and hostname validation, verify the downloaded image and checksum, and install only into an inactive OTA slot. It SHALL preserve existing settings and autonomous regulation when the update service, Wi-Fi, or MQTT is unavailable.

#### Scenario: Assigned update is installed
- **WHEN** a newer assigned release is available and conditions are safe
- **THEN** Therment SHALL download and validate the image, write the inactive slot, and restart
- **AND** it SHALL confirm the image only after local startup safety checks succeed

#### Scenario: Unsafe or failed update
- **WHEN** a relay is active, the download is interrupted, or the image is invalid
- **THEN** Therment SHALL NOT restart for installation while a relay is active or activate an invalid or incomplete image
- **AND** it SHALL report waiting or failure as appropriate

#### Scenario: New image fails startup
- **WHEN** the new image fails its startup checks or restarts before confirmation
- **THEN** the device SHALL use the previous working slot where rollback is possible and report the outcome when connectivity returns
- **AND** it SHALL preserve relay interlock, sensor-failure shutdown, and compressor restart delay

### Requirement: Update state and manual requests are visible
Therment SHALL provide an MQTT Update entity in Home Assistant with installed and available versions, availability, and in-progress status; it SHALL expose progress when known and provide a diagnostic outcome. Home Assistant SHALL be able to request an immediate update check using a non-retained command that carries no firmware URL. Duplicate or repeated commands SHALL NOT cause concurrent updates.

#### Scenario: Manual request
- **WHEN** an operator selects Install in Home Assistant
- **THEN** Therment SHALL request an immediate check and apply the same assignment and safety checks as an automatic update
- **AND** a separate diagnostic check button SHALL be available when Home Assistant considers the firmware up to date

#### Scenario: Device offline or update fails
- **WHEN** the device disconnects or an update fails or rolls back
- **THEN** Home Assistant SHALL NOT present a stale in-progress update as a current successful update
- **AND** the latest available diagnostic outcome SHALL distinguish failure or rollback from success

### Requirement: Deployment is observable by an agent
The deployment tool SHALL allow an authorized agent to publish, assign, trigger, and follow an update without displaying credentials. It SHALL produce human-readable events and stable machine-readable status including device, release, action, phase, and outcome, and SHALL allow a later read-only status query.

#### Scenario: Successful deployment
- **WHEN** the device confirms the assigned version after reboot
- **THEN** the tool SHALL report success for the matching action and release

#### Scenario: Timeout or lost connection
- **WHEN** the tool cannot observe a confirmed final result before its configurable timeout
- **THEN** it SHALL report a non-success pending or unknown result and a nonzero exit status
- **AND** a later invocation SHALL be able to inspect the same action

### Requirement: USB migration and recovery preserve safety
The initial OTA installation SHALL use a documented USB migration that checks device identity, flash capacity, and existing partitions and preserves or securely restores NVS settings. Firmware and flash backups SHALL be treated as secrets.

#### Scenario: Migration prerequisites are not met
- **WHEN** the device layout is unknown, the image does not fit, or a verified recovery path is unavailable
- **THEN** the migration SHALL stop before changing flash contents

#### Scenario: Device is recovered
- **WHEN** a controlled recovery is performed
- **THEN** the documented procedure SHALL restore a working firmware and retained settings or explicitly verify their recovery
