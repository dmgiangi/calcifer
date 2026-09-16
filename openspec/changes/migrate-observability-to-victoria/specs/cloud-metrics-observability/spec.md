## MODIFIED Requirements

### Requirement: Kubernetes metrics collection
The system SHALL collect cluster, node, workload, pod, and container metrics from `calcifer-cloud` and deliver them to VictoriaMetrics using Prometheus remote write.

#### Scenario: A scrape target is reachable
- **WHEN** Alloy discovers a configured Kubernetes or node metrics target
- **THEN** Alloy SHALL scrape the target and deliver its samples to VictoriaMetrics.

#### Scenario: A target cannot be scraped
- **WHEN** a configured metrics target is unavailable
- **THEN** Alloy SHALL expose a scrape failure metric and continue scraping the remaining targets.

### Requirement: PromQL metrics queries
The system SHALL expose current and historical metrics through a Prometheus-compatible VictoriaMetrics endpoint for Grafana.

#### Scenario: Querying current metrics
- **WHEN** Grafana submits a valid PromQL query for recent samples
- **THEN** VictoriaMetrics SHALL return matching samples from its local storage.

#### Scenario: Querying historical metrics
- **WHEN** Grafana submits a valid PromQL query covering retained historical samples
- **THEN** VictoriaMetrics SHALL return matching samples within its configured retention period.

### Requirement: Metrics retention ownership
The system SHALL enforce retention of persisted metric data through VictoriaMetrics runtime retention settings on its local persistent volume configured to a 365-day retention period (`retentionPeriod: 365d`).

#### Scenario: Samples exceed configured retention
- **WHEN** persisted metric parts exceed the configured 365-day retention period
- **THEN** VictoriaMetrics SHALL prune expired data partitions during background merge operations.

### Requirement: Observability platform meta-monitoring
The system SHALL collect metrics from Grafana, Grafana Operator, Alloy, VictoriaMetrics, VictoriaLogs, and VictoriaTraces.

#### Scenario: An observability component is healthy
- **WHEN** an observability component exposes its metrics endpoint
- **THEN** Alloy SHALL scrape the endpoint and deliver its metrics to VictoriaMetrics.

#### Scenario: An observability component cannot be scraped
- **WHEN** Alloy cannot scrape an observability component's metrics endpoint
- **THEN** the scrape failure SHALL be visible through the VictoriaMetrics datasource.
