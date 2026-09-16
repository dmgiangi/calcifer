# Grafana observability management

## Purpose

Define declarative Grafana lifecycle management, data sources, dashboards,
credentials, API access, and protected HTTPS exposure.

## Requirements

### Requirement: Declarative Grafana lifecycle
The system SHALL manage the Grafana instance through Grafana Operator custom resources reconciled by Flux.

#### Scenario: Flux reconciles observability manifests
- **WHEN** Flux reconciles the observability Kustomization
- **THEN** Grafana Operator SHALL reconcile the declared Grafana instance.

### Requirement: Provisioned observability datasources
The system SHALL provision VictoriaMetrics as a Prometheus-compatible datasource, VictoriaLogs as a log datasource, and VictoriaTraces as a Jaeger-compatible tracing datasource without manual Grafana UI configuration.

#### Scenario: Grafana starts after datasource reconciliation
- **WHEN** the Grafana instance and backend Services are ready
- **THEN** Grafana SHALL contain queryable VictoriaMetrics, VictoriaLogs, and VictoriaTraces datasources.

### Requirement: Declarative baseline dashboard
The system SHALL provision a declarative baseline dashboard set covering cluster metrics, workload logs, and the health of Grafana, Grafana Operator, Alloy, VictoriaMetrics, VictoriaLogs, and VictoriaTraces without manual Grafana UI configuration.

#### Scenario: Grafana dashboard reconciliation
- **WHEN** Grafana Operator reconciles the declared dashboard resources
- **THEN** the Grafana instance SHALL contain the baseline observability dashboards.

### Requirement: Community observability dashboards
The system SHALL provision standard community dashboards from Grafana.com via Grafana Operator `GrafanaDashboard` resources, covering Kubernetes cluster views, Node Exporter, VictoriaTraces, and Victoria operational health.

#### Scenario: Declarative dashboard provisioning
- **WHEN** Grafana Operator reconciles declared `GrafanaDashboard` resources with `grafanaCom` references
- **THEN** Grafana SHALL import and activate the dashboards in the designated folder without manual UI import.

#### Scenario: Broken dashboard handling
- **WHEN** an upstream community dashboard contains queries incompatible with the deployed metrics
- **THEN** the implementation SHALL omit or replace the dashboard rather than leaving broken panels active.

### Requirement: Generated Grafana administrator credential
The system SHALL configure a local Grafana user named `admin` with a cryptographically random password stored exclusively in a SOPS-encrypted Kubernetes Secret.

#### Scenario: Initial Grafana deployment
- **WHEN** Grafana is first deployed
- **THEN** it SHALL source the `admin` username and generated password from the encrypted administrator credential Secret.

#### Scenario: Source control inspection
- **WHEN** Grafana manifests are committed to Git
- **THEN** no plaintext administrator password SHALL be present in committed content.

### Requirement: Grafana API service authentication
The system SHALL provision a distinct Grafana service account and finite-lifetime service-account token for authenticated Grafana HTTP API access.

#### Scenario: API automation authenticates
- **WHEN** an authorized client sends the service-account token as a Bearer credential to the Grafana HTTPS API
- **THEN** Grafana SHALL authorize requests permitted by the service account's configured role.

#### Scenario: Service token persistence
- **WHEN** Grafana Operator creates or rotates the service-account token
- **THEN** it SHALL store the token in a Kubernetes Secret in the `monitoring` namespace and no token value SHALL be committed to Git.

### Requirement: Live observability acceptance gate
The change SHALL be considered complete only after Flux and kubectl report the observability platform ready and Grafana's HTTP API returns successful results for VictoriaMetrics PromQL, VictoriaLogs, and VictoriaTraces.

#### Scenario: Complete end-to-end validation
- **WHEN** Flux reports the observability Kustomization and HelmReleases Ready, kubectl reports required pods ready, and an authorized client queries datasources through Grafana's HTTPS API
- **THEN** the API SHALL return successful query results for metrics, logs, and traces.

#### Scenario: Failed live validation
- **WHEN** any Flux readiness check, Kubernetes readiness check, Grafana API authentication, or backend query fails
- **THEN** the change SHALL remain incomplete.

### Requirement: Protected Grafana access
The system SHALL expose only Grafana through HTTPS and SHALL source its administrator credential from a SOPS-encrypted Kubernetes Secret.

#### Scenario: Accessing Grafana over HTTPS
- **WHEN** an operator opens the configured Grafana hostname
- **THEN** Traefik SHALL serve Grafana with a certificate issued by the production cert-manager issuer.

#### Scenario: Accessing Grafana without valid credentials
- **WHEN** a user does not provide valid Grafana credentials
- **THEN** Grafana SHALL deny authenticated UI access.
