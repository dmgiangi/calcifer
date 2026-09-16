# Observability Blob Storage

## Purpose

Define isolated, encrypted, and network-restricted Azure Blob storage for
Velero backups of observability data.

## Requirements

### Requirement: Isolated Azure Blob containers
The system SHALL use a dedicated, private Azure Blob container in the `calciferobs` storage account for Velero offsite disaster recovery backups of observability volumes, without active database read/write connections from runtime pods.

#### Scenario: Velero performs offsite backup
- **WHEN** Velero executes a scheduled or manual backup of observability volumes
- **THEN** it SHALL write backup archives exclusively to the designated backup container in `calciferobs`.

#### Scenario: Runtime observability pods operate
- **WHEN** VictoriaMetrics, VictoriaLogs, or VictoriaTraces runs
- **THEN** it SHALL NOT require direct network connectivity or credentials to Azure Blob Storage during normal operation.

### Requirement: Encrypted observability storage credentials
The system SHALL store Azure Blob credentials for Velero exclusively in SOPS-encrypted Kubernetes Secret manifests, using dedicated credentials limited to the backup container.

#### Scenario: Observability configuration is committed
- **WHEN** observability backup manifests are committed to Git
- **THEN** no plaintext Azure account key, SAS token, connection string, client secret, or other credential SHALL be present in the committed content.

#### Scenario: Velero starts
- **WHEN** Velero and its node-agent start
- **THEN** they SHALL receive object-store credentials from a dedicated Kubernetes Secret limited to the backup storage location.

### Requirement: Restricted storage network access
The Azure storage account SHALL permit Blob data-plane access from the `calcifer-cloud` VPS address `136.144.222.128` and SHALL deny anonymous blob access.

#### Scenario: Backup access from the cluster VPS
- **WHEN** Velero accesses the backup Blob container from `136.144.222.128` with valid credentials
- **THEN** Azure Blob Storage SHALL authorize the request.

#### Scenario: Anonymous Blob access
- **WHEN** a request does not include valid Azure authorization
- **THEN** Azure Blob Storage SHALL deny access to backup data.
