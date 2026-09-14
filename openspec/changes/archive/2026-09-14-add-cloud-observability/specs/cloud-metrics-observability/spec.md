## ADDED Requirements

### Requirement: Kubernetes metrics collection
The system SHALL collect cluster, node, workload, pod, and container metrics from `calcifer-cloud` and deliver them to Thanos Receive using Prometheus remote write.

#### Scenario: A scrape target is reachable
- **WHEN** Alloy discovers a configured Kubernetes or node metrics target
- **THEN** Alloy SHALL scrape the target and deliver its samples to Thanos Receive.

#### Scenario: A target cannot be scraped
- **WHEN** a configured metrics target is unavailable
- **THEN** Alloy SHALL expose a scrape failure metric and continue scraping the remaining targets.

### Requirement: PromQL metrics queries
The system SHALL expose current and historical metrics through a Prometheus-compatible Thanos Query endpoint for Grafana.

#### Scenario: Querying current metrics
- **WHEN** Grafana submits a valid PromQL query for recent samples
- **THEN** Thanos Query SHALL return samples available from Thanos Receive.

#### Scenario: Querying uploaded historical metrics
- **WHEN** Grafana submits a valid PromQL query whose time range includes uploaded blocks
- **THEN** Thanos Query SHALL include matching blocks from Azure Blob Storage in the result.

### Requirement: Metrics retention ownership
The system SHALL use Thanos Compactor as the only configured mechanism that deletes persisted metric blocks.

#### Scenario: A block exceeds configured retention
- **WHEN** a persisted metric block exceeds the configured metrics retention period
- **THEN** Thanos Compactor SHALL mark and remove the block according to Thanos retention processing.

### Requirement: Observability platform meta-monitoring
The system SHALL collect metrics from Grafana, Grafana Operator, Alloy, every deployed Thanos component, and Loki.

#### Scenario: An observability component is healthy
- **WHEN** an observability component exposes its metrics endpoint
- **THEN** Alloy SHALL scrape the endpoint and deliver its metrics to Thanos Receive.

#### Scenario: An observability component cannot be scraped
- **WHEN** Alloy cannot scrape an observability component's metrics endpoint
- **THEN** the scrape failure SHALL be visible through the Thanos datasource.
