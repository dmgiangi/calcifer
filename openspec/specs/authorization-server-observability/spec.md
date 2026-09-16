# Authorization server observability

## Purpose

Define health, metrics, logs, resource bounds, and acceptance observability
for the Cloud and Home authorization-server instances.

## Requirements

### Requirement: Both authorization instances are observable
The Cloud and Home authorization-server workloads SHALL expose liveness,
readiness, Prometheus endpoints, cluster role, state mode, active Redis
generation when available, Redis reachability, transition counts, and recovery
outcomes, and SHALL be discovered by the local Alloy scraping configuration.
Readiness SHALL represent the ability to serve stateful authorization requests,
while liveness SHALL not fail solely because Home is isolated or Redis is
unavailable. Structured logs SHALL be collected by the local Loki path without
credentials, token values, authorization codes, session identifiers, email
labels, password data, or Redis secrets.

#### Scenario: Connected instance is healthy
- **WHEN** either pod can use the active Redis generation and no recovery gate is active
- **THEN** readiness SHALL succeed and telemetry SHALL report `CONNECTED` with the generation

#### Scenario: Home is isolated
- **WHEN** Home is healthy but cannot reach Redis and has entered `ISOLATED`
- **THEN** liveness and mode telemetry SHALL remain healthy while readiness reflects that Home can serve isolated stateful requests

#### Scenario: Recovery gate is active
- **WHEN** automatic generation recovery is draining stateful requests
- **THEN** readiness SHALL fail or report unavailable for stateful traffic while liveness remains healthy

### Requirement: Both instances have explicit resource bounds
Each cluster SHALL run one authorization-server replica with explicit CPU and
memory requests and limits appropriate to that cluster's available headroom.

#### Scenario: Scheduler evaluates either workload
- **WHEN** Flux applies the Cloud or Home Deployment
- **THEN** the pod SHALL specify explicit resource requests and limits

### Requirement: Acceptance covers both availability paths
The change SHALL not be complete until Cloud and Home readiness, telemetry, the
canonical Google callback, password login, canonical claims, machine-token
Grafana access, Redis-backed restart persistence, isolation, ambiguous Redis
failure handling, and automatic generation recovery have been verified through
their respective paths without printing secrets, sessions, authorization codes,
or tokens.

#### Scenario: Home loses Internet access
- **WHEN** Google, Redis Cloud, and external Internet are unavailable to Home long enough to trigger isolation
- **THEN** LAN password login and local application access SHALL succeed using a fresh isolation epoch

#### Scenario: Home becomes unavailable
- **WHEN** Home cannot serve requests
- **THEN** Cloud applications SHALL still authenticate through the Cloud
  instance

#### Scenario: Private transit is restored
- **WHEN** an acceptance test restores Home-to-Cloud Redis connectivity after isolated authorization activity
- **THEN** telemetry SHALL show one automatic recovery, a new generation, discarded local state, and successful fresh authentication without administrative commands

#### Scenario: Redis operation has an ambiguous timeout
- **WHEN** an acceptance test interrupts the response to a connected Redis mutation
- **THEN** telemetry SHALL show request failure and SHALL show no local retry of that operation

### Requirement: Dedicated Redis storage is monitored
The Cloud observability stack SHALL collect Redis availability, persistence,
memory, rejected-write, connection, and operation-latency metrics and SHALL
alert on loss of availability, AOF failure, approaching memory limits, or
unexpected public reachability without logging ACL credentials or stored values.

#### Scenario: Redis rejects state writes
- **WHEN** Redis reports persistence failure, memory exhaustion, or rejected commands
- **THEN** an actionable alert SHALL identify the Cloud Redis service and failure class without exposing authorization data
