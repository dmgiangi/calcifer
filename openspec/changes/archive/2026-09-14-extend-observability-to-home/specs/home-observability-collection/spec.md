## ADDED Requirements

### Requirement: Home Kubernetes metrics collection
The system SHALL run Grafana Alloy on `calcifer-home` and collect Kubernetes,
node, workload, pod, and container metrics for that cluster.

#### Scenario: A Home metrics target is reachable
- **WHEN** Alloy discovers a configured Home Kubernetes or node metrics target
- **THEN** it SHALL scrape the target and attach `cluster="calcifer-home"`
  before forwarding the samples to the central Thanos Receive endpoint

#### Scenario: A Home metrics target is unavailable
- **WHEN** a configured Home metrics target cannot be scraped
- **THEN** Alloy SHALL expose a scrape failure metric and continue processing
  the remaining targets

### Requirement: Home workload log collection
The system SHALL collect stdout and stderr logs from Kubernetes workloads on
`calcifer-home` and attach stable cluster, namespace, pod, and container
identity labels.

#### Scenario: A Home workload emits a log line
- **WHEN** a Home container writes to stdout or stderr
- **THEN** Alloy SHALL forward the line to the central Loki endpoint with
  `cluster="calcifer-home"` and the workload identity labels

#### Scenario: A Home workload is recreated
- **WHEN** Kubernetes replaces a Home workload Pod
- **THEN** Alloy SHALL discover the replacement and continue collecting logs
  without manual target configuration

### Requirement: Durable Home forwarding buffer
The system SHALL persist Alloy Home forwarding state on a dedicated local
volume and SHALL retain unsent metric and log data while the central endpoints
are unavailable, up to the configured disk and age limits.

#### Scenario: The Cloud transit is unavailable
- **WHEN** the WireGuard or private ingest endpoint is unavailable
- **THEN** Alloy Home SHALL write pending metric and log data to its persistent
  local WAL/queue and SHALL expose backlog or retry health metrics

#### Scenario: The Cloud transit is restored
- **WHEN** the private ingest endpoints become reachable again
- **THEN** Alloy Home SHALL retry pending data and resume normal forwarding
  without duplicating successfully acknowledged samples or log batches

#### Scenario: The forwarding volume approaches capacity
- **WHEN** the configured WAL volume reaches its operational threshold
- **THEN** Alloy SHALL expose an actionable disk or drop signal and SHALL not
  silently report the forwarding path as healthy

### Requirement: Home Alloy self-monitoring
The system SHALL collect the health, scrape, queue, WAL, retry, and drop
metrics exposed by Alloy Home through the central metrics pipeline.

#### Scenario: Home Alloy is healthy
- **WHEN** Alloy Home exposes its metrics endpoint
- **THEN** the central Thanos datasource SHALL contain metrics identifying the
  Home Alloy instance and cluster
