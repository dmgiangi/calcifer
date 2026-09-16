## ADDED Requirements

### Requirement: Unified observability PVC backup
The system SHALL configure scheduled Velero File System Backups powered by Kopia to capture the persistent volumes of VictoriaMetrics, VictoriaLogs, and VictoriaTraces.

#### Scenario: Scheduled backup execution
- **WHEN** the scheduled Velero backup triggers
- **THEN** Velero node-agent SHALL back up the contents of all matching observability PVCs to the designated Azure Blob backup storage location using deduplication and encryption.

#### Scenario: Backup completion status
- **WHEN** a backup run finishes
- **THEN** Velero SHALL report `Completed` status and record volume backup statistics.

### Requirement: Disaster recovery restore
The system SHALL support restoring VictoriaMetrics, VictoriaLogs, and VictoriaTraces PVC data from Azure Blob Storage into newly provisioned local PVCs.

#### Scenario: Restoring observability data after cluster or volume failure
- **WHEN** an operator initiates a Velero restore targeting an observability backup
- **THEN** Velero SHALL hydrate the persistent volume contents from Azure Blob into local volumes and allow the observability workloads to resume operation.
