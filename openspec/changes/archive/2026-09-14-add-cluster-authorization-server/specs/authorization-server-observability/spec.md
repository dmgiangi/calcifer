## MODIFIED Requirements

### Requirement: Both authorization instances are observable
The Cloud and Home authorization-server workloads SHALL expose liveness,
readiness, and Prometheus endpoints and SHALL be discovered by the local Alloy
scraping configuration. Their structured logs SHALL be collected by the local
Loki path without credentials, token values, authorization codes, email labels,
or password data.

#### Scenario: Cloud and Home pods are healthy
- **WHEN** either pod is Ready
- **THEN** its health and process metrics SHALL reach the corresponding
  observability stack

### Requirement: Both instances have explicit resource bounds
Each cluster SHALL run one authorization-server replica with explicit CPU and
memory requests and limits appropriate to that cluster's available headroom.

#### Scenario: Scheduler evaluates either workload
- **WHEN** Flux applies the Cloud or Home Deployment
- **THEN** the pod SHALL specify explicit resource requests and limits

### Requirement: Acceptance covers both availability paths
The change SHALL not be complete until Cloud and Home readiness, telemetry,
Google login, password login, canonical claims, and machine-token Grafana
access have been verified through their respective paths without printing
secrets or tokens.

#### Scenario: Home loses Internet access
- **WHEN** Google and external Internet are unavailable to Home
- **THEN** LAN password login and local application access SHALL still succeed

#### Scenario: Home becomes unavailable
- **WHEN** Home cannot serve requests
- **THEN** Cloud applications SHALL still authenticate through the Cloud
  instance
