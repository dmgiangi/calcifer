## ADDED Requirements

### Requirement: Isolated Azure Blob containers
The system SHALL use private, separate Azure Blob containers for Thanos metrics and Loki logs in the `calciferobs` storage account.

#### Scenario: Thanos accesses metrics storage
- **WHEN** a Thanos component reads or writes metric blocks
- **THEN** it SHALL access only the configured private `thanos` container and `calcifer-cloud` prefix.

#### Scenario: Loki accesses log storage
- **WHEN** Loki reads or writes log data
- **THEN** it SHALL access only the configured private `loki` container.

### Requirement: Encrypted observability storage credentials
The system SHALL store Azure Blob SAS connection strings exclusively in SOPS-encrypted Kubernetes Secret manifests.

#### Scenario: Observability configuration is committed
- **WHEN** observability manifests are committed to Git
- **THEN** no plaintext Azure account key, SAS token, or connection string SHALL be present in the committed content.

#### Scenario: A backend starts
- **WHEN** Thanos or Loki starts
- **THEN** it SHALL receive its object-store credential from its dedicated Kubernetes Secret.

### Requirement: Restricted storage network access
The Azure storage account SHALL permit Blob data-plane access from the `calcifer-cloud` VPS address `136.144.222.128` and SHALL deny anonymous blob access.

#### Scenario: Backend access from the cluster VPS
- **WHEN** an observability backend accesses its Blob container from `136.144.222.128` with a valid SAS token
- **THEN** Azure Blob Storage SHALL authorize the request.

#### Scenario: Anonymous Blob access
- **WHEN** a request does not include valid Azure authorization
- **THEN** Azure Blob Storage SHALL deny access to observability data.
