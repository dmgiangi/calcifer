## ADDED Requirements

### Requirement: Shared dashboard deployment
The system SHALL deploy Homepage in both `calcifer-cloud` and `calcifer-home` using a shared base and environment-specific overlays, with the dashboard reachable at the canonical hostname `https://calcifer.tech`.

#### Scenario: Dashboard is deployed in both clusters
- **WHEN** the GitOps configuration for either cluster is reconciled
- **THEN** Homepage is deployed using the common catalog configuration and the cluster's ingress/TLS resources

#### Scenario: LAN access uses the canonical hostname
- **WHEN** a client on the home LAN resolves `calcifer.tech`
- **THEN** split-horizon DNS routes the request to the Home cluster without requiring Internet connectivity

### Requirement: Declarative service catalog
The system SHALL load the Homepage service catalog from a versioned `services.yaml` ConfigMap and SHALL NOT require Kubernetes API access or RBAC permissions for service discovery.

#### Scenario: Catalog contains core services
- **WHEN** Homepage starts
- **THEN** the catalog contains links for the Authorization Server and Grafana with their canonical HTTPS URLs

#### Scenario: New service is added
- **WHEN** an operator adds a service entry to `services.yaml`
- **THEN** Homepage displays the service after the configuration rollout without Java code changes

### Requirement: HTTP service monitoring
The system SHALL monitor each catalogued service using Homepage HTTP `siteMonitor` checks against the configured canonical URL.

#### Scenario: Reachable service
- **WHEN** the monitored HTTPS URL is reachable and responds successfully
- **THEN** Homepage reports the service as available

#### Scenario: Cloud service unavailable from Home
- **WHEN** Home cannot reach a Cloud-hosted service
- **THEN** Homepage in Home reports that service as unavailable while retaining the service link

### Requirement: OIDC-protected dashboard
The system SHALL protect Homepage with native OIDC authentication using `https://auth.calcifer.tech` as issuer and SHALL keep client/session secrets out of the public catalog ConfigMap.

#### Scenario: Authenticated user opens the dashboard
- **WHEN** a user completes the OIDC authorization-code flow
- **THEN** Homepage establishes an authenticated session and renders the catalog

#### Scenario: Unauthenticated user opens the dashboard
- **WHEN** a user requests `https://calcifer.tech` without a valid Homepage session
- **THEN** Homepage redirects the user to the Authorization Server for authentication
