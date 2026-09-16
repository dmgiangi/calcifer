# home-assistant-backup Specification

## Purpose
TBD - created by archiving change add-home-assistant-home-edge. Update Purpose after archive.
## Requirements
### Requirement: Automated periodic backup of Home Assistant configuration to Azure Blob Storage
The system SHALL run a scheduled backup job on `calcifer-home` to archive the Home Assistant `/config` volume and upload encrypted snapshots to a dedicated, private Azure Blob Storage container.

#### Scenario: Scheduled backup execution
- **WHEN** the scheduled backup CronJob triggers
- **THEN** it SHALL mount the Home Assistant configuration volume, safely snapshot the configuration and SQLite database, compress or archive the state, and upload it to the designated Azure Blob container.

#### Scenario: Backup job retains historical snapshots
- **WHEN** a backup completes successfully
- **THEN** previous snapshots SHALL be retained according to a retention policy (e.g. 7 daily snapshots) before older snapshots are pruned.

#### Scenario: Backup upload uses static Cloud egress
- **WHEN** the backup job connects to Azure Blob Storage from the dynamic residential network
- **THEN** HTTPS SHALL traverse the private WireGuard link and a restricted Cloud CONNECT proxy so Azure observes the allowlisted Cloud VPS egress IP while TLS remains end-to-end.

### Requirement: Secure credential management for backup operations
The system SHALL store Azure Blob credentials exclusively in SOPS-encrypted Kubernetes Secret manifests scoped to the Home Assistant backup container.

#### Scenario: Backup job accesses storage with container-scoped credentials
- **WHEN** the backup job initializes on `calcifer-home`
- **THEN** it SHALL read credentials from the SOPS-decrypted Secret and authenticate to Azure Blob Storage without exposing plain-text keys in Git or container logs.

#### Scenario: Anonymous access is denied
- **WHEN** an unauthenticated request attempts to access the backup container
- **THEN** Azure Blob Storage SHALL deny the request.

#### Scenario: Egress proxy use is restricted
- **WHEN** a client other than the Home WireGuard peer or a destination other than the configured Azure Blob endpoint attempts to use the proxy
- **THEN** the proxy SHALL deny the connection.

