# Spec Delta

## MODIFIED Requirements

### Requirement: Isolated Azure Blob containers

The system SHALL use a dedicated, private Azure Blob container in the migrated Italy North Storage account for Velero offsite disaster recovery backups of observability volumes, without active database read/write connections from runtime pods.

#### Scenario: Velero performs offsite backup
- **WHEN** Velero executes a scheduled or manual backup of observability volumes
- **THEN** it SHALL write backup archives exclusively to the designated backup container in the migrated Storage account

#### Scenario: Runtime observability pods operate
- **WHEN** VictoriaMetrics, VictoriaLogs, or VictoriaTraces runs
- **THEN** it SHALL NOT require direct network connectivity or credentials to Azure Blob Storage during normal operation

### Requirement: Restricted storage network access

The Azure Storage account SHALL permit Blob data-plane access from the `calcifer-cloud` VPS address `136.144.222.128` and SHALL deny anonymous blob access. The restriction SHALL be implemented with the Storage firewall rather than a dependency on the retired `vnet01`.

#### Scenario: Backup access from the cluster VPS
- **WHEN** Velero accesses the backup Blob container from `136.144.222.128` with valid credentials
- **THEN** Azure Blob Storage SHALL authorize the request

#### Scenario: Anonymous Blob access
- **WHEN** a request does not include valid Azure authorization
- **THEN** Azure Blob Storage SHALL deny access to backup data
