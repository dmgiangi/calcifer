# Observability backup recovery

## Purpose

Define scheduled file-system backups and disaster recovery restores for
VictoriaMetrics and VictoriaTraces local persistent volumes using Velero and
Kopia on `calcifer-cloud`.

## Requirements

### Requirement: Selected observability PVC backup
The system SHALL configure scheduled Velero File System Backups powered by Kopia to capture the persistent volumes of VictoriaMetrics and VictoriaTraces while excluding VictoriaLogs, and SHALL store the resulting backup data in the migrated Azure Blob backup location.

#### Scenario: Scheduled backup execution
- **WHEN** the scheduled Velero backup triggers
- **THEN** Velero node-agent SHALL back up the metrics and traces PVC contents to the migrated Azure Blob backup storage location using deduplication and encryption.

#### Scenario: VictoriaLogs volume is discovered
- **WHEN** Velero processes the VictoriaLogs pod during a scheduled or manual observability backup
- **THEN** it SHALL exclude the VictoriaLogs data volume from File System Backup and SHALL NOT upload its log data to Azure Blob Storage.

#### Scenario: Backup completion status
- **WHEN** a backup run finishes
- **THEN** Velero SHALL report `Completed` status and record volume backup statistics.

### Requirement: Disaster recovery restore
The system SHALL support restoring VictoriaMetrics and VictoriaTraces PVC data from the migrated Azure Blob Storage location into newly provisioned local PVCs.

#### Scenario: Restoring observability data after cluster or volume failure
- **WHEN** an operator initiates a Velero restore targeting an observability backup
- **THEN** Velero SHALL hydrate the persistent volume contents from the migrated Azure Blob location into local volumes and allow the observability workloads to resume operation.
