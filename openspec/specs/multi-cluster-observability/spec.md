# Multi-cluster observability

## Purpose

Define cluster identity, Grafana querying, and operational dashboards for the
centralized Cloud and Home observability platform.

## Requirements

### Requirement: Central backends preserve cluster identity
The central Thanos and Loki pipelines SHALL retain an unambiguous cluster
identity for every metric series and log stream received from Cloud or Home.

#### Scenario: Cloud and Home expose the same metric name
- **WHEN** both clusters send a metric with the same metric name and workload
  labels
- **THEN** Thanos SHALL retain distinct series addressable by
  `cluster="calcifer-cloud"` and `cluster="calcifer-home"`

#### Scenario: Cloud and Home emit equivalent logs
- **WHEN** equivalent workloads emit logs in both clusters
- **THEN** Loki SHALL retain distinct streams addressable by their cluster
  label and workload identity labels

### Requirement: Grafana queries both clusters
Grafana SHALL continue to use the local Thanos and Loki datasources while
allowing authenticated users and API clients to query data from both clusters.

#### Scenario: Query Home metrics through Grafana
- **WHEN** an authenticated Grafana client submits a PromQL query filtered by
  `cluster="calcifer-home"`
- **THEN** Grafana SHALL return the matching Home samples through Thanos

#### Scenario: Query Home logs through Grafana
- **WHEN** an authenticated Grafana client submits a LogQL query filtered by
  `cluster="calcifer-home"`
- **THEN** Grafana SHALL return matching Home logs through Loki

### Requirement: Multi-cluster operational dashboards
The observability dashboards SHALL provide a cluster selector and SHALL show
collection and backend health for both clusters.

#### Scenario: Operator selects a cluster
- **WHEN** an operator selects `calcifer-home` or `calcifer-cloud` in a Grafana
  dashboard variable
- **THEN** the metric and log panels SHALL scope their queries to the selected
  cluster without requiring manual dashboard edits

#### Scenario: Operator investigates Home forwarding
- **WHEN** the Home tunnel or backend path is degraded
- **THEN** Grafana SHALL expose Alloy retry, backlog, scrape-failure, or drop
  signals sufficient to distinguish collection failure from backend failure

### Requirement: End-to-end Home acceptance
The change SHALL not be considered complete until a Home workload's metrics and
log line are queryable through Grafana's authenticated HTTPS API.

#### Scenario: Home data reaches Grafana
- **WHEN** Kubernetes resources are ready and a Home workload emits a
  uniquely identifiable log line
- **THEN** Grafana's datasource API SHALL return non-empty PromQL and LogQL
  results containing the Home cluster label and expected log line
