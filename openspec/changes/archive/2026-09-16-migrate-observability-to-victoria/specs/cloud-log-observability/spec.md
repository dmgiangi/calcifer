## RENAMED Requirements

- FROM: `### Requirement: LogQL log queries`
- TO: `### Requirement: LogsQL log queries`

## MODIFIED Requirements

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
The system SHALL persist log data on a local ext4 PersistentVolumeClaim attached to VictoriaLogs, managed through built-in retention partitioning configured to a 14-day retention period (`retentionPeriod: 14d`).

#### Scenario: Log retention expiration
- **WHEN** log data partitions exceed the configured 14-day retention period
- **THEN** VictoriaLogs SHALL prune the expired daily partitions from local storage.
