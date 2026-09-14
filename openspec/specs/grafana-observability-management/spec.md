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
The system SHALL provision Thanos Query as a Prometheus datasource and Loki as a Loki datasource without manual Grafana UI configuration.

#### Scenario: Grafana starts after datasource reconciliation
- **WHEN** the Grafana instance and backend Services are ready
- **THEN** Grafana SHALL contain queryable Thanos and Loki datasources.

### Requirement: Declarative baseline dashboard
The system SHALL provision at least one Grafana dashboard that displays cluster metrics, links to workload logs, and presents the health of Grafana, Grafana Operator, Alloy, Thanos, and Loki without manual Grafana UI configuration.

#### Scenario: Grafana dashboard reconciliation
- **WHEN** Grafana Operator reconciles the declared dashboard resource
- **THEN** the Grafana instance SHALL contain the baseline observability dashboard.

### Requirement: Open-source Loki and Thanos dashboards
The system SHALL provision compatible open-source operational dashboards for Loki and Thanos when dashboards are available from the upstream Loki and Thanos monitoring mixins for the pinned component versions.

#### Scenario: Loki dashboard provisioning
- **WHEN** a version-matched Loki mixin dashboard is available and its queries can run against the deployed Loki metrics and Thanos PromQL datasource
- **THEN** Grafana Operator SHALL provision the Loki dashboard in the observability folder with the deployed datasource UID and without requiring manual UI import.

#### Scenario: Thanos dashboard provisioning
- **WHEN** a version-matched Thanos mixin dashboard is available and its queries can run against the deployed Loki metrics and Thanos PromQL datasource
- **THEN** Grafana Operator SHALL provision the Thanos dashboard in the observability folder with the deployed datasource UID and without requiring manual UI import.

#### Scenario: Upstream dashboard incompatibility
- **WHEN** an upstream dashboard is unavailable, requires an undeployed component, or contains queries incompatible with the pinned metrics and labels
- **THEN** the implementation SHALL omit or adapt that dashboard, retain the baseline self-monitoring dashboard, and record the reason in validation output rather than importing a broken dashboard.

#### Scenario: Dashboard API validation
- **WHEN** the live acceptance test uses the Grafana service-account token
- **THEN** it SHALL verify the expected Loki and Thanos dashboard UIDs/titles and execute representative panel queries through Grafana's datasource API.

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
The change SHALL be considered complete only after Flux and kubectl report the observability platform ready and Grafana's HTTP API returns successful, non-empty results for both a Thanos PromQL query and a Loki LogQL query.

#### Scenario: Complete end-to-end validation
- **WHEN** Flux reports the observability Kustomization and HelmReleases Ready, kubectl reports required resources ready, and an authorized client queries both datasources through Grafana's HTTPS API
- **THEN** the API SHALL return a successful PromQL result from Thanos and a successful LogQL result containing the expected workload log line.

#### Scenario: Failed live validation
- **WHEN** any Flux readiness check, Kubernetes readiness check, Grafana API authentication, PromQL query, or LogQL query fails or lacks its expected result
- **THEN** the change SHALL remain incomplete.

### Requirement: Protected Grafana access
The system SHALL expose only Grafana through HTTPS and SHALL source its administrator credential from a SOPS-encrypted Kubernetes Secret.

#### Scenario: Accessing Grafana over HTTPS
- **WHEN** an operator opens the configured Grafana hostname
- **THEN** Traefik SHALL serve Grafana with a certificate issued by the production cert-manager issuer.

#### Scenario: Accessing Grafana without valid credentials
- **WHEN** a user does not provide valid Grafana credentials
- **THEN** Grafana SHALL deny authenticated UI access.
