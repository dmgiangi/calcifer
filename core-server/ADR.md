---
title: "Architecture Decision Records (ADR)"
subtitle: "Architectural Decisions for Calcifer Digital Twin Project"
author: "Calcifer Team"
date: last-modified
lang: en
format:
  pdf:
    documentclass: scrartcl
    papersize: a4
    toc: true
    toc-depth: 3
    number-sections: true
    colorlinks: true
    linkcolor: "calcifer-blue"
    urlcolor: "calcifer-red"
    geometry:
      - top=30mm
      - left=25mm
      - right=25mm
      - bottom=30mm
    fig-width: 6
    fig-height: 4
    filters:
      - _quarto_temp/utils/resize-images.lua
    include-in-header:
      text: |
        \usepackage{pagecolor}
        \usepackage{afterpage}
        \definecolor{calcifer-blue}{HTML}{0B2C4B}
        \definecolor{calcifer-red}{HTML}{E63946}
    include-before-body:
      file: _quarto_temp/utils/before-body.tex
    highlight-style: github
    code-block-bg: "#f8f8f8"
    code-block-border-left: "#0B2C4B"
---

# Architecture Decision Records (ADR)

This document consolidates all architectural decisions made during Phase 0 of the Calcifer Digital Twin project.

## Table of Contents

1. [ADR-001: Data Persistence Strategy](#adr-001-data-persistence-strategy)
2. [ADR-002: FunctionalSystem Nature](#adr-002-functionalsystem-nature)
3. [ADR-003: Failure Modes & Resilience](#adr-003-failure-modes--resilience)
4. [ADR-004: SpEL Security](#adr-004-spel-security)
5. [ADR-005: Override Stacking & Conflicts](#adr-005-override-stacking--conflicts)
6. [ADR-006: Event Reliability](#adr-006-event-reliability)
7. [ADR-007: Async Feedback](#adr-007-async-feedback)
8. [ADR-008: Bounded Context](#adr-008-bounded-context)
9. [ADR-009: Concurrency Control](#adr-009-concurrency-control)
10. [ADR-010: Observability](#adr-010-observability)
11. [ADR-011: Global Exception Handling](#adr-011-global-exception-handling)
12. [ADR-012: Input Validation](#adr-012-input-validation)
13. [ADR-013: Reconciler Efficiency](#adr-013-reconciler-efficiency)
14. [ADR-014: TestController Removal](#adr-014-testcontroller-removal)
15. [ADR-015: Redis Key TTL Strategy](#adr-015-redis-key-ttl-strategy)
16. [ADR-016: AMQP Dead Letter Queue](#adr-016-amqp-dead-letter-queue)
17. [ADR-017: Health Checks](#adr-017-health-checks)
18. [ADR-018: Event Idempotency](#adr-018-event-idempotency)
19. [ADR-019: Rule Engine Choice](#adr-019-rule-engine-choice)

---

## ADR-001: Data Persistence Strategy

**Status:** Accepted  
**Date:** 2024-01  
**Severity:** HIGH

### Context

Need to decide how to synchronize data between MongoDB and Redis for the Digital Twin system.

### Decision

**Hybrid persistence with clear separation:**

| Data Type                                     | Storage                         | Rationale                      |
|-----------------------------------------------|---------------------------------|--------------------------------|
| Device State (real-time)                      | Redis only                      | Low latency, high throughput   |
| Configuration (FunctionalSystem, SafetyRules) | MongoDB only                    | Durability, queryability       |
| Audit Log                                     | MongoDB only                    | Compliance, historical queries |
| Override                                      | Write-through (MongoDB + Redis) | Source of truth + active cache |

**Override warmup:** Load active overrides from MongoDB to Redis on startup.

### Consequences

- No complex sync patterns needed
- Clear ownership of data
- Redis failure doesn't lose configuration
- MongoDB failure doesn't block real-time operations

---

## ADR-002: FunctionalSystem Nature

**Status:** Accepted  
**Date:** 2024-01  
**Severity:** HIGH

### Context

Define what FunctionalSystem represents and how it relates to devices.

### Decision

**Approach C - Lightweight Aggregate:**

- FunctionalSystem is a DDD Aggregate Root persisted in MongoDB
- Owns CONFIGURATION and DEVICE MEMBERSHIP only
- Device states remain in Redis (per ADR-001)
- FunctionalSystemSnapshot is a transient view assembled on-demand

**Key semantics:**

- Device auto-registration: devices can exist standalone, be added to systems later
- Exclusive membership: device belongs to max one system
- Deferred validation: allows devices to arrive in any order

### Consequences

- Clean separation between configuration and state
- Supports gradual device onboarding
- Prevents device ownership conflicts

---

## ADR-003: Failure Modes & Resilience

**Status:** Accepted (Modified)  
**Date:** 2024-01  
**Severity:** HIGH

### Context

Define how the system behaves when infrastructure components fail.

### Decision

**Fail-Stop Pattern (not Circuit Breaker):**

Original decision proposed Resilience4j circuit breakers, but this was **rejected** for IoT safety reasons.

**Rationale:** If Redis/MongoDB are unavailable, it's safer to stop generating commands (fail-stop) rather than use
fallback with potentially stale data. IoT devices have hardware fail-safes.

**Implementation:**

1. `InfrastructureHealthGate` monitors Redis/MongoDB health
2. Reconciler checks `isHealthy()` before generating commands
3. If unhealthy, skip command generation with WARN log
4. Publish `InfrastructureFailureEvent` for alerting

**Hardcoded safety rules** (e.g., fire_temp > 80°C → pump ON) are always evaluated, even when SpEL engine fails.

### Consequences

- No stale data risk
- Devices rely on hardware fail-safes during outages
- Clear alerting for infrastructure issues
- Simpler than circuit breaker pattern

---

## ADR-004: SpEL Security

**Status:** Accepted  
**Date:** 2024-01  
**Severity:** HIGH

### Context

SpEL expressions in safety rules could be exploited for code injection.

### Decision

**Defense in Depth:**

| Layer | Protection                                              |
|-------|---------------------------------------------------------|
| 1     | `SimpleEvaluationContext` - blocks dangerous operations |
| 2     | AST whitelist validation at rule load time              |
| 3     | Configurable execution timeout (default 100ms)          |
| 4     | Audit logging of all evaluations                        |

**Rule Categories (fixed precedence):**

```
HARDCODED_SAFETY > SYSTEM_SAFETY > EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL > USER_INTENT
```

**Versioning:** Rules are versioned in MongoDB with explicit activation. Supports rollback and simulation.

### Consequences

- SpEL injection attacks mitigated
- Clear precedence for conflict resolution
- Audit trail for compliance

---

## ADR-005: Override Stacking & Conflicts

**Status:** Accepted  
**Date:** 2024-01  
**Severity:** HIGH

### Context

Multiple overrides can exist for the same device. Need conflict resolution rules.

### Decision

**Categorized Overrides:**

```
EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL
```

**Stacking semantics:**

- One override per (target, category) pair
- Higher category shadows lower
- On expiry, next category takes over

**Conflict resolution:**

1. Higher category wins regardless of scope
2. Same category: DEVICE wins over SYSTEM (more specific)
3. Same category + scope: most recent wins

**Safety interaction:**

- HARDCODED_SAFETY and SYSTEM_SAFETY cannot be overridden
- EMERGENCY bypass deferred to future auth phase

**TTL:** Optional - overrides can be permanent until cancelled.

### Consequences

- Predictable override behavior
- Safety rules always win
- Supports maintenance windows and emergency shutdowns

---

## ADR-006: Event Reliability

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Events can be lost or duplicated. Need reliability guarantees.

### Decision

**At-Least-Once Delivery:**

- In-memory retry: 3 attempts with exponential backoff (1s, 2s, 4s)
- Hard failures: persist to MongoDB + alert
- Dead Letter Queue for unprocessable messages

**Idempotency:**

- Redis-based deduplication with 5min TTL
- Key pattern: `idempotency:{messageId}`
- Shared across instances

**Debounce:**

- 50ms window per device (configurable)
- Accumulates rapid events, processes final state only
- Only for feedback events (temperature is time-series)

**Backpressure:**

- AMQP prefetch limit (default 10)
- Natural backpressure to message broker

**Async Processing:**

- `@Async` event listeners with dedicated thread pool
- 4 core, 8 max threads, 100 queue capacity
- `CallerRunsPolicy` for overflow

### Consequences

- No message loss
- No duplicate processing
- Graceful degradation under load

---

## ADR-007: Async Feedback

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Clients need real-time feedback on device state changes.

### Decision

**Hybrid approach:**

| Mechanism                     | Use Case                 |
|-------------------------------|--------------------------|
| WebSocket (STOMP over SockJS) | Real-time dashboards     |
| Polling (GET /twin)           | Fallback, simple clients |

**WebSocket topics:**

- `/topic/devices/{id}` - device state changes
- `/topic/systems/{id}` - system state changes
- `/topic/overrides` - override events

**Message types:**

- `intent_accepted`, `intent_rejected`, `intent_modified`
- `desired_calculated`
- `device_converged`, `device_diverged`
- `override_applied`, `override_blocked`, `override_expired`

**Deferred:**

- Webhook notifications (future phase)
- Reactive API (future major version)

### Consequences

- Real-time updates for dashboards
- Simple polling for basic clients
- No complex reactive refactoring needed

---

## ADR-008: Bounded Context

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Define module boundaries and shared kernel.

### Decision

**Logical separation via packages:**

```
domain.twin        - Digital Twin core
domain.temperature - Temperature monitoring
domain.system      - FunctionalSystem
domain.shared      - Shared kernel
```

**Shared Kernel:**

- `DeviceId`, `DeviceType`, `DeviceCapability`
- `DeviceValue` hierarchy (`RelayValue`, `FanValue`)
- Changes require cross-context review

**ACL Structure (Protocol Adapter Pattern):**

```
infrastructure/adapter/{mqtt,amqp,rest}/
  MqttProtocolAdapter
  MqttPayloadCodec
  MqttDeviceIdMapper
```

Isolates firmware protocol details from domain.

### Consequences

- Clear package boundaries
- Shared kernel is explicit
- Protocol changes don't leak into domain

---

## ADR-009: Concurrency Control

**Status:** Accepted
**Date:** 2024-01
**Severity:** HIGH

### Context

Concurrent updates can cause data corruption.

### Decision

**Read-Modify-Write Protection:**

- Compare-And-Swap with version field
- `saveDesiredStateIfVersion()` method
- Retry on conflict

**MongoDB Versioning:**

- Spring Data `@Version` annotation
- Automatic optimistic locking
- Throws `OptimisticLockingFailureException` on conflict

**Distributed Locks:**

- Avoid for MVP
- Design operations to be idempotent
- Accept eventual consistency for non-critical paths

**Retry Strategy:**

- Configurable per operation type
- Default: exponential backoff
- Allow fail-fast for time-sensitive operations

**Conflict Logging:**

- WARN level + publish `ApplicationEvent`
- Include correlationId for tracing

### Consequences

- No data corruption from concurrent updates
- Clear conflict resolution
- No distributed lock complexity

---

## ADR-010: Observability

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Need visibility into system behavior for debugging and compliance.

### Decision

**Distributed Tracing:**

- Micrometer Tracing with OpenTelemetry
- Auto-propagates trace context across HTTP, AMQP, events
- Integrates with Zipkin/Jaeger

**Audit Trail:**

- MongoDB collection: `decision_audit`
- Fields: correlationId, timestamp, deviceId, systemId, decisionType, actor, previousValue, newValue, reason, context
- Queryable for compliance and debugging

**Metrics (Micrometer + Prometheus):**

- `intent.received`, `intent.rejected`
- `convergence.time`, `convergence.timeout`
- `devices.active`
- `reconciler.cycle`, `reconciler.devices.*`
- `safety.activation`
- `override.active`

**Health Checks:**

- Custom `HealthIndicator` beans
- Redis, RabbitMQ, MongoDB, Reconciler
- Readiness vs Liveness probes

**Logging:**

- JSON in prod (Logstash encoder)
- Plain text in dev
- Always include correlationId, deviceId in MDC

### Consequences

- Full request tracing
- Compliance-ready audit trail
- Prometheus-compatible metrics
- Clear health status

---

## ADR-011: Global Exception Handling

**Status:** Accepted
**Date:** 2024-01
**Severity:** HIGH

### Context

Need consistent error responses across all endpoints.

### Decision

**Format:** RFC 7807 ProblemDetail (Spring Boot 3+ native)

**Exception Mapping:**

| Exception                            | HTTP Status |
|--------------------------------------|-------------|
| `IllegalArgumentException`           | 400         |
| `MethodArgumentNotValidException`    | 400         |
| `ConstraintViolationException`       | 400         |
| `DeviceNotFoundException`            | 404         |
| `OptimisticLockingFailureException`  | 409         |
| `SafetyRuleViolationException`       | 422         |
| `OverrideBlockedException`           | 422         |
| `InfrastructureUnavailableException` | 503         |
| `Exception`                          | 500         |

**Error Codes (enum):**
`VALIDATION_ERROR`, `PARSE_ERROR`, `NOT_FOUND`, `CONFLICT`, `SAFETY_BLOCK`, `INFRASTRUCTURE_DOWN`, `INTERNAL_ERROR`

**Correlation:** Include correlationId from MDC in all responses.

**Stack Traces:** Never in prod, optional in dev.

### Consequences

- Consistent error format
- Client-parseable error codes
- Correlation for debugging

---

## ADR-012: Input Validation

**Status:** Accepted
**Date:** 2024-01
**Severity:** HIGH

### Context

Need to validate user input to prevent invalid states.

### Decision

**Value Field Strategy:**

- Keep `Object` type with custom `@ValidIntentRequest` constraint
- Cross-field validation: RELAY→Boolean, FAN→Integer 0-4

**Path Variable Validation:**

- `@NotBlank` + `@Pattern(regexp="^[a-zA-Z0-9_-]+$")`
- Prevents injection, ensures safe characters

**Controller Setup:**

- `@Validated` on controller class
- `@Valid` on `@RequestBody`

**Error Detail Level:**

- Field-level errors with messages + rejected value
- `{field, message, rejectedValue}` for debugging

### Consequences

- Type-safe value validation
- Injection prevention
- Detailed error messages

---

## ADR-013: Reconciler Efficiency

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Reconciler should not send unnecessary commands.

### Decision

**Convergence Check:**

- Check `isConverged()` before sending commands
- Only reconcile devices where reported != desired

**Metrics:**

- `calcifer.reconciler.devices.reconciled` (Counter)
- `calcifer.reconciler.devices.skipped` (Counter)
- `calcifer.reconciler.devices.failed` (Counter)
- `calcifer.reconciler.cycle.duration` (Timer)

**Logging Levels:**

- TRACE: skipped (converged) devices
- DEBUG: actual reconciliations
- WARN: no-snapshot edge case
- ERROR: failed reconciliations

### Consequences

- Reduced network traffic
- Clear visibility into reconciler behavior
- Early detection of data inconsistencies

---

## ADR-014: TestController Removal

**Status:** Accepted
**Date:** 2024-01
**Severity:** HIGH

### Context

TestController bypasses domain logic and poses security risks.

### Decision

**Remove TestController.java entirely.**

**Rationale:**

1. Bypasses domain logic - sends commands without UserIntent/DesiredState flow
2. Security risk - no authentication, no authorization, no audit trail
3. State inconsistency - commands without DesiredState update causes twin drift
4. Alternatives exist - use `/api/devices/{id}/intent` or direct MQTT tools

**Future admin bypass:** Should go through Override system with proper auth and audit.

### Consequences

- No security backdoor
- Consistent state management
- Proper audit trail for all commands

---

## ADR-015: Redis Key TTL Strategy

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Redis keys can accumulate over time. Need cleanup strategy.

### Decision

**Device Key TTL:** No TTL

- State should persist while device exists
- TTL would cause loss of UserIntent/DesiredState if device goes offline

**Staleness Detection:**

- Track `lastActivity` timestamp in Hash
- Scheduled job (daily 3 AM) detects devices with no activity >7 days
- Log/alert for investigation (no auto-delete)

**Orphan Cleanup:**

- Daily job (4 AM) scans `index:active:outputs`
- Removes entries where device key no longer exists

**Delete API:**

- `deleteDevice(DeviceId)` in DeviceStateRepository
- Deletes Hash key + removes from index
- Used for explicit decommissioning

### Consequences

- No accidental data loss
- Stale devices detected
- Index stays clean

---

## ADR-016: AMQP Dead Letter Queue

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Failed messages need to be captured for debugging.

### Decision

**DLQ Scope:** All queues get corresponding `.dlq` queue

**Dead Letter Exchange:** Single shared `dlx.exchange` (DirectExchange)

**Retry Strategy:**

- Spring Retry: 3 attempts with exponential backoff (1s, 2s, 4s)
- Then reject without requeue → DLQ
- `RejectAndDontRequeueRecoverer`

**DLQ Monitoring:**

- `@RabbitListener` logs dead letters with full context
- Increments `calcifer.dlq.messages` counter for alerting

**Reprocessing:** Admin endpoint to move messages from DLQ back to main queue.

### Consequences

- No message loss
- Clear visibility into failures
- Manual reprocessing possible

---

## ADR-017: Health Checks

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Need to expose health status for Kubernetes probes.

### Decision

**Custom HealthIndicator beans:**

- Redis: ping test, connection pool status
- RabbitMQ: connection status, channel availability
- MongoDB: ping test
- Reconciler: track lastReconciliationTime, alert if stale >30s

**Probes:**

- Liveness: app is running
- Readiness: app can serve traffic (dependencies healthy)

**Aggregation:** `/actuator/health` aggregates all indicators, DOWN if any critical dependency is DOWN.

### Consequences

- Kubernetes-ready health checks
- Clear dependency status
- Automatic pod restart on failure

---

## ADR-018: Event Idempotency

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Duplicate events can cause incorrect state.

### Decision

**Idempotency Key Source:**

- AMQP messageId (primary)
- Content hash fallback (deviceId + timestamp + value)

**Enforcement Point:**

- IntegrationFlow filter before event publishing
- Deduplicate at AMQP layer

**Deduplication:**

- Redis SETNX with 5min TTL
- Key pattern: `idempotency:{messageId}`
- `IdempotencyService.tryAcquire(key)`

**Debounce Scope:**

- Only feedback events (ActuatorFeedbackReceivedEvent)
- Temperature events pass through (time-series data)

### Consequences

- No duplicate processing
- Minimal overhead (Redis SETNX)
- Time-series data preserved

---

## ADR-019: Rule Engine Choice

**Status:** Accepted
**Date:** 2024-01
**Severity:** MEDIUM

### Context

Need a rule engine for safety rules. Options: Drools vs Easy Rules.

### Decision

**Easy Rules + SpEL** instead of Drools.

**Rationale:**

| Aspect     | Easy Rules     | Drools               |
|------------|----------------|----------------------|
| Complexity | Simple IF-THEN | Complex inference    |
| Size       | ~50KB          | ~10MB+               |
| Startup    | Instant        | Slow (compilation)   |
| Syntax     | YAML + SpEL    | DRL (specialized)    |
| Hot Reload | @RefreshScope  | KieContainer rebuild |

**Trade-offs Accepted:**

- No backward chaining (not needed)
- No complex pattern matching (not needed)
- No rule versioning in engine (handled by MongoDB)

**Dependencies:**

- `org.jeasy:easy-rules-core:4.1.0`
- `org.jeasy:easy-rules-spel:4.1.0`

### Consequences

- Lightweight footprint
- Fast startup
- Operator-readable rules
- Spring-native integration

