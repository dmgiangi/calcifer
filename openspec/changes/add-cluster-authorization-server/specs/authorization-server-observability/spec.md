## ADDED Requirements

### Requirement: Authorization server is observable through existing stack
The authorization-server workload SHALL expose Actuator liveness, readiness, and Prometheus endpoints and SHALL be discovered by existing Alloy scraping. Its structured logs SHALL be collected by Loki. Metrics and logs SHALL not contain credentials, token values, authorization codes, or email labels.

#### Scenario: Healthy pod is scraped
- **WHEN** the authorization-server pod is Ready
- **THEN** Thanos SHALL receive its health and process metrics through Alloy

#### Scenario: Authentication activity is emitted
- **WHEN** the server handles login, token, or ForwardAuth activity
- **THEN** Grafana's Loki datasource SHALL query a corresponding structured event without secret or email values

### Requirement: Authorization server has operational dashboard and resource bounds
Grafana SHALL provision an `Authorization Server` dashboard in the existing Observability folder showing availability, request/error rate, login/token and ForwardAuth outcomes, and pod resources. Cloud SHALL run one replica with explicit CPU and memory requests and limits sized for the current node.

#### Scenario: Operator investigates service health
- **WHEN** an operator opens the Authorization Server dashboard
- **THEN** it SHALL show availability, outcome, and resource panels from the configured datasources

#### Scenario: Scheduler evaluates workload placement
- **WHEN** Flux applies the authorization-server Deployment
- **THEN** its pod SHALL specify explicit CPU/memory requests and limits and one replica

### Requirement: Live acceptance verifies identity and observability end to end
The change SHALL not be complete until Flux/workload readiness, metrics/log arrival, Google browser login, and a client-credentials JWT query for both Thanos and Loki through Grafana HTTPS all succeed. The procedure SHALL not print a secret or token.

#### Scenario: Release validation succeeds
- **WHEN** acceptance runs after Flux reconciliation
- **THEN** it SHALL verify readiness, telemetry, browser OIDC, and authenticated Grafana datasource results without emitting secret values

#### Scenario: Required end-to-end check fails
- **WHEN** any readiness, OIDC, machine-token, metric, log, or query check fails
- **THEN** the change SHALL remain incomplete and Grafana password login SHALL not be disabled as final migration
