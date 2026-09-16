## MODIFIED Requirements

### Requirement: Provisioned observability datasources
The system SHALL provision VictoriaMetrics as a Prometheus-compatible datasource, VictoriaLogs as a log datasource, and VictoriaTraces as a Jaeger-compatible tracing datasource without manual Grafana UI configuration.

#### Scenario: Grafana starts after datasource reconciliation
- **WHEN** the Grafana instance and backend Services are ready
- **THEN** Grafana SHALL contain queryable VictoriaMetrics, VictoriaLogs, and VictoriaTraces datasources.

### Requirement: Open-source Loki and Thanos dashboards
The system SHALL provision standard community dashboards from Grafana.com via Grafana Operator `GrafanaDashboard` resources, covering Kubernetes cluster views, Node Exporter, VictoriaTraces, and Victoria operational health.

#### Scenario: Declarative dashboard provisioning
- **WHEN** Grafana Operator reconciles declared `GrafanaDashboard` resources with `grafanaCom` references
- **THEN** Grafana SHALL import and activate the dashboards in the designated folder without manual UI import.

#### Scenario: Broken dashboard handling
- **WHEN** an upstream community dashboard contains queries incompatible with the deployed metrics
- **THEN** the implementation SHALL omit or replace the dashboard rather than leaving broken panels active.

### Requirement: Live observability acceptance gate
The change SHALL be considered complete only after Flux and kubectl report the observability platform ready and Grafana's HTTP API returns successful results for VictoriaMetrics PromQL, VictoriaLogs, and VictoriaTraces.

#### Scenario: Complete end-to-end validation
- **WHEN** Flux reports the observability Kustomization and HelmReleases Ready, kubectl reports required pods ready, and an authorized client queries datasources through Grafana's HTTPS API
- **THEN** the API SHALL return successful query results for metrics, logs, and traces.

#### Scenario: Failed live validation
- **WHEN** any Flux readiness check, Kubernetes readiness check, Grafana API authentication, or backend query fails
- **THEN** the change SHALL remain incomplete.
