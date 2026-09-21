# Spec Delta

## MODIFIED Requirements

### Requirement: Automated periodic backup of Home Assistant configuration to Azure Blob Storage

The system SHALL run a scheduled backup job on `calcifer-home` to archive the Home Assistant `/config` volume and upload encrypted snapshots to the designated private container in the migrated Italy North Azure Blob Storage account.

#### Scenario: Scheduled backup execution
- **WHEN** the scheduled backup CronJob triggers
- **THEN** it SHALL mount the Home Assistant configuration volume, safely snapshot the configuration and SQLite database, compress or archive the state, and upload it to the designated Azure Blob container

#### Scenario: Backup job retains historical snapshots
- **WHEN** a backup completes successfully
- **THEN** previous snapshots SHALL be retained according to a retention policy (e.g. 7 daily snapshots) before older snapshots are pruned

#### Scenario: Backup upload uses static Cloud egress
- **WHEN** the backup job connects to Azure Blob Storage from the dynamic residential network
- **THEN** HTTPS SHALL traverse the private WireGuard link and a restricted Cloud CONNECT proxy so Azure observes the allowlisted Cloud VPS egress IP while TLS remains end-to-end
