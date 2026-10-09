# Cloud log observability

## Purpose

Define workload log collection, querying, and durable storage for
`calcifer-cloud`.

## Requirements

### Requirement: Kubernetes workload log collection
The system SHALL collect stdout and stderr logs from Kubernetes workloads on `calcifer-cloud` using Grafana Alloy and forward them to VictoriaLogs using the Loki push protocol.

#### Scenario: A workload emits a log line
- **WHEN** a container writes to stdout or stderr
- **THEN** Alloy SHALL forward the log line to VictoriaLogs with cluster, namespace, pod, and container identity labels.

#### Scenario: A workload is restarted
- **WHEN** Kubernetes recreates a workload pod
- **THEN** Alloy SHALL continue collecting logs from the replacement pod without manual configuration.

### Requirement: LogsQL log queries
The system SHALL expose VictoriaLogs as a log query datasource to Grafana while keeping the VictoriaLogs API private to the cluster.

#### Scenario: Querying workload logs in Grafana
- **WHEN** an authenticated Grafana user submits a valid log query
- **THEN** Grafana SHALL return matching logs from VictoriaLogs.

#### Scenario: Direct external VictoriaLogs access
- **WHEN** a request originates outside the Kubernetes cluster and targets the VictoriaLogs API
- **THEN** the request SHALL not reach a publicly exposed VictoriaLogs Service or Ingress.

### Requirement: Durable log storage
The system SHALL persist log data on the existing local ext4 PersistentVolumeClaim
attached to VictoriaLogs, managed through built-in retention partitioning
configured to six backend-native months (`retentionPeriod: 6M`). This shared policy
SHALL cover infrastructure logs and both external clients. Disk-pressure cleanup
SHALL be documented separately and SHALL NOT be represented as guaranteeing six
months of actual retained history.

#### Scenario: Log retention expiration
- **WHEN** log data partitions exceed the configured six-month time-retention period
- **THEN** VictoriaLogs SHALL prune the expired daily partitions from local storage.

#### Scenario: Retention setting is increased
- **WHEN** time retention changes from 14 days to six months
- **THEN** existing retained data and its PVC SHALL be preserved and previously expired data SHALL not be claimed as recovered.

#### Scenario: Disk cleanup limit is reached before six months
- **WHEN** storage pressure causes older partitions to be removed before their time-retention age
- **THEN** operational telemetry and documentation SHALL distinguish this early cleanup from age-based expiration.
