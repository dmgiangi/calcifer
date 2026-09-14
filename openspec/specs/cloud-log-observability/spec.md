# Cloud log observability

## Purpose

Define workload log collection, querying, and durable storage for
`calcifer-cloud`.

## Requirements

### Requirement: Kubernetes workload log collection
The system SHALL collect stdout and stderr logs from Kubernetes workloads on `calcifer-cloud` using Grafana Alloy.

#### Scenario: A workload emits a log line
- **WHEN** a container writes to stdout or stderr
- **THEN** Alloy SHALL forward the log line to Loki with cluster, namespace, pod, and container identity labels.

#### Scenario: A workload is restarted
- **WHEN** Kubernetes recreates a workload pod
- **THEN** Alloy SHALL continue collecting logs from the replacement pod without manual configuration.

### Requirement: LogQL log queries
The system SHALL expose Loki as a LogQL datasource to Grafana while keeping the Loki API private to the cluster.

#### Scenario: Querying workload logs in Grafana
- **WHEN** an authenticated Grafana user submits a valid LogQL query
- **THEN** Grafana SHALL return matching logs from Loki.

#### Scenario: Direct external Loki access
- **WHEN** a request originates outside the Kubernetes cluster and targets the Loki API
- **THEN** the request SHALL not reach a publicly exposed Loki Service or Ingress.

### Requirement: Durable log storage
The system SHALL persist Loki log chunks and indexes to Azure Blob Storage using the TSDB storage mode.

#### Scenario: Loki flushes a log block
- **WHEN** Loki flushes eligible log data from its ingest path
- **THEN** it SHALL write the corresponding chunk and index data to the Loki Azure Blob container.
