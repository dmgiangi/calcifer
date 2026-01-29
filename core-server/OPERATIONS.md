---
title: "Calcifer Core Server - Operations Guide"
subtitle: "Production Deployment, Monitoring, and Maintenance Reference"
author: "Calcifer Team"
date: "\\today"
lang: "en"
titlepage: true
titlepage-color: "0B2C4B"
titlepage-text-color: "FFFFFF"
titlepage-rule-color: "E63946"
titlepage-rule-height: 2
toc: true
toc-own-page: true
listings: true
---

# Calcifer Core Server - Operations Guide

This guide covers operational aspects of running and maintaining the Calcifer Core Server in production.

## Table of Contents

1. [Safety Rules Configuration](#safety-rules-configuration)
2. [MongoDB Collections](#mongodb-collections)
3. [Monitoring & Observability](#monitoring--observability)
4. [Operational Procedures](#operational-procedures)

---

## Safety Rules Configuration

### Rule Categories (Precedence Order)

| Category           | Priority    | Description                                   |
|--------------------|-------------|-----------------------------------------------|
| `HARDCODED_SAFETY` | Highest     | Java-based rules, cannot be overridden        |
| `SYSTEM_SAFETY`    | High        | System-level safety rules (YAML configurable) |
| `EMERGENCY`        | Medium-High | Emergency overrides                           |
| `MAINTENANCE`      | Medium      | Maintenance overrides                         |
| `SCHEDULED`        | Low-Medium  | Scheduled overrides                           |
| `MANUAL`           | Low         | Manual user overrides                         |
| `USER_INTENT`      | Lowest      | Normal user intent                            |

### Hardcoded Safety Rules (Java)

These rules are always evaluated and cannot be disabled:

| Rule                    | Description                    | Behavior              |
|-------------------------|--------------------------------|-----------------------|
| `PumpFireInterlockRule` | Prevents fire OFF when pump ON | REFUSES fire OFF      |
| `FirePumpInterlockRule` | Forces pump ON when fire ON    | MODIFIES pump to ON   |
| `MaxFanSpeedRule`       | Enforces max fan speed (4)     | MODIFIES speed to max |

### Configurable Rules (YAML + SpEL)

Location: `src/main/resources/safety-rules.yml`

#### SpEL Context Variables

| Variable         | Type        | Description                                   |
|------------------|-------------|-----------------------------------------------|
| `#deviceId`      | DeviceId    | Device identifier (controllerId, componentId) |
| `#deviceType`    | DeviceType  | RELAY, FAN, TEMPERATURE_SENSOR                |
| `#proposedValue` | DeviceValue | Proposed value to validate                    |
| `#currentValue`  | DeviceValue | Current desired value (may be null)           |
| `#reportedValue` | DeviceValue | Reported device state (may be null)           |
| `#systemType`    | String      | FunctionalSystem type (may be null)           |
| `#metadata`      | Map         | Additional context (temperature, hour, etc.)  |

#### Rule Definition Structure

```yaml
safety:
  rules:
    - id: UNIQUE_RULE_ID           # Required: unique identifier
      name: Human Readable Name    # Required: display name
      description: What it does    # Optional: documentation
      category: SYSTEM_SAFETY      # Required: precedence category
      priority: 100                # Required: within-category priority (lower = higher)
      enabled: true                # Optional: default true
      version: 1                   # Optional: for tracking changes
      condition: "#deviceType.name() == 'FAN'"  # SpEL: when to apply
      action: MODIFY               # ACCEPT, REFUSE, or MODIFY
      spelExpression: |            # SpEL: what to do
        #proposedValue.speed() > 2 ? 
          new dev.dmgiangi.core.server.domain.model.FanValue(2) : 
          #proposedValue
      reason: "Explanation for audit log"
```

#### Rule Actions

| Action   | Description      | SpEL Expression Returns |
|----------|------------------|-------------------------|
| `ACCEPT` | Allow the change | Boolean (true = accept) |
| `REFUSE` | Block the change | Boolean (true = refuse) |
| `MODIFY` | Modify the value | DeviceValue (new value) |

#### Example Rules

**Temperature-Based Fan Limit:**

```yaml
- id: TEMP_FAN_LIMIT
  name: Temperature-Based Fan Speed Limit
  category: SYSTEM_SAFETY
  priority: 100
  condition: "#deviceType.name() == 'FAN' and #metadata['temperature'] != null"
  action: MODIFY
  spelExpression: |
    #metadata['temperature'] < 30 and #proposedValue.speed() > 2 ? 
      new dev.dmgiangi.core.server.domain.model.FanValue(2) : 
      #proposedValue
  reason: "Fan speed limited to 2 when temperature below 30°C"
```

**Night Mode Restriction:**

```yaml
- id: NIGHT_MODE_RELAY
  name: Night Mode Relay Restriction
  category: SCHEDULED
  priority: 50
  enabled: false  # Enable via config override
  condition: "#deviceType.name() == 'RELAY' and #metadata['hour'] != null"
  action: REFUSE
  spelExpression: "#metadata['hour'] >= 22 or #metadata['hour'] < 6"
  reason: "Relay activation not allowed during night hours (22:00-06:00)"
```

#### SpEL Security Restrictions

Per security sandboxing (Phase 0.4):

- ❌ No method calls on arbitrary objects
- ❌ No constructor calls (except DeviceValue types)
- ❌ No static method access
- ❌ No reflection
- ⏱️ Timeout: 100ms per rule evaluation

#### Global Settings

```yaml
safety:
  settings:
    evaluationTimeoutMs: 100    # SpEL timeout per rule
    failOpen: false             # false = refuse on error (safer)
    logLevel: DEBUG             # TRACE, DEBUG, INFO
```

---

## MongoDB Collections

### Connection Configuration

**application.yaml:**

```yaml
spring:
  data:
    mongodb:
      database: calcifer
      auto-index-creation: true
```

**application-dev.yaml (or environment-specific):**

```yaml
spring:
  data:
    mongodb:
      host: localhost
      port: 27017
      username: calcifer_user
      password: calcifer_password
      authentication-database: admin
```

### Collections Overview

| Collection           | Purpose                     | TTL               | Indexes                                                              |
|----------------------|-----------------------------|-------------------|----------------------------------------------------------------------|
| `functional_systems` | FunctionalSystem aggregates | No                | `deviceIds`                                                          |
| `device_overrides`   | Active overrides            | Yes (`expiresAt`) | `targetId+category` (unique)                                         |
| `decision_audit`     | Audit trail                 | No*               | `correlationId`, `timestamp`, `deviceId`, `systemId`, `decisionType` |

*Consider adding TTL index for audit retention policy.

### Collection: `functional_systems`

```javascript
// Document structure
{
    "_id"
:
    "uuid-string",
        "type"
:
    "TERMOCAMINO",           // TERMOCAMINO, HVAC, IRRIGATION, GENERIC
        "name"
:
    "Living Room Fireplace",
        "configuration"
:
    {
        "mode"
    :
        "AUTO",                // OFF, MANUAL, AUTO, ECO, BOOST, AWAY
            "targetTemperature"
    :
        22.0,
            "scheduleStart"
    :
        "06:00",
            "scheduleEnd"
    :
        "22:00",
            "safetyThresholds"
    :
        {
            "maxTemperature"
        :
            85.0,
                "criticalTemperature"
        :
            95.0,
                "maxFanSpeed"
        :
            4
        }
    }
,
    "deviceIds"
:
    [                   // Exclusive membership
        "esp32-001:pump",
        "esp32-001:fire",
        "esp32-001:fan"
    ],
        "failSafeDefaults"
:
    {
        "esp32-001:pump"
    :
        true,        // Pump ON as fail-safe
            "esp32-001:fire"
    :
        false        // Fire OFF as fail-safe
    }
,
    "createdAt"
:
    ISODate("2024-01-15T10:00:00Z"),
        "updatedAt"
:
    ISODate("2024-01-15T12:30:00Z"),
        "createdBy"
:
    "admin",
        "version"
:
    3                     // Optimistic locking
}
```

**Useful Queries:**

```javascript
// Find system by device
db.functional_systems.findOne({deviceIds: "esp32-001:pump"})

// Find all TERMOCAMINO systems
db.functional_systems.find({type: "TERMOCAMINO"})

// Find systems in AUTO mode
db.functional_systems.find({"configuration.mode": "AUTO"})
```

### Collection: `device_overrides`

```javascript
// Document structure
{
    "_id"
:
    "esp32-001:pump:MAINTENANCE",  // targetId:category
        "targetId"
:
    "esp32-001:pump",
        "scope"
:
    "DEVICE",                    // DEVICE or SYSTEM
        "category"
:
    "MAINTENANCE",            // EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL
        "value"
:
    {
        "state"
    :
        true
    }
,           // DeviceValue (RelayValue or FanValue)
    "reason"
:
    "Scheduled pump maintenance",
        "expiresAt"
:
    ISODate("2024-01-16T18:00:00Z"),  // TTL index
        "createdAt"
:
    ISODate("2024-01-15T10:00:00Z"),
        "createdBy"
:
    "maintenance-bot",
        "version"
:
    1
}
```

**Useful Queries:**

```javascript
// Find all active overrides for a device
db.device_overrides.find({
    targetId: "esp32-001:pump",
    $or: [
        {expiresAt: null},
        {expiresAt: {$gt: new Date()}}
    ]
})

// Find all EMERGENCY overrides
db.device_overrides.find({category: "EMERGENCY"})

// Find expired overrides (should be auto-deleted by TTL)
db.device_overrides.find({expiresAt: {$lt: new Date()}})
```

### Collection: `decision_audit`

```javascript
// Document structure
{
    "_id"
:
    "uuid-string",
        "correlationId"
:
    "trace-id-from-otel",
        "timestamp"
:
    ISODate("2024-01-15T10:30:00Z"),
        "deviceId"
:
    "esp32-001:pump",
        "systemId"
:
    "termocamino-living-room",
        "decisionType"
:
    "SAFETY_RULE_ACTIVATED",
        "actor"
:
    "SafetyRuleEngine",
        "previousValue"
:
    {
        "state"
    :
        false
    }
,
    "newValue"
:
    {
        "state"
    :
        true
    }
,
    "reason"
:
    "FirePumpInterlockRule: Pump must be ON when fire is ON",
        "context"
:
    {
        "ruleId"
    :
        "FirePumpInterlockRule",
            "ruleCategory"
    :
        "HARDCODED_SAFETY",
            "triggerDevice"
    :
        "esp32-001:fire"
    }
}
```

**Decision Types:**

- `INTENT_RECEIVED`, `INTENT_REJECTED`, `INTENT_MODIFIED`
- `DESIRED_CALCULATED`
- `OVERRIDE_APPLIED`, `OVERRIDE_BLOCKED`, `OVERRIDE_EXPIRED`
- `SAFETY_RULE_ACTIVATED`
- `DEVICE_CONVERGED`, `DEVICE_DIVERGED`
- `FALLBACK_ACTIVATED`, `FAIL_SAFE_APPLIED`

**Useful Queries:**

```javascript
// Find all decisions for a device in last hour
db.decision_audit.find({
    deviceId: "esp32-001:pump",
    timestamp: {$gt: new Date(Date.now() - 3600000)}
}).sort({timestamp: -1})

// Find all safety rule activations
db.decision_audit.find({decisionType: "SAFETY_RULE_ACTIVATED"})

// Trace a request by correlationId
db.decision_audit.find({correlationId: "abc123"}).sort({timestamp: 1})
```

---

## Monitoring & Observability

### Actuator Endpoints

| Endpoint                     | Description                                          |
|------------------------------|------------------------------------------------------|
| `/actuator/health`           | Health status (Redis, MongoDB, RabbitMQ, Reconciler) |
| `/actuator/health/liveness`  | Kubernetes liveness probe                            |
| `/actuator/health/readiness` | Kubernetes readiness probe                           |
| `/actuator/prometheus`       | Prometheus metrics                                   |
| `/actuator/metrics`          | Micrometer metrics                                   |
| `/actuator/info`             | Application info                                     |

### Key Metrics

#### Reconciler Metrics

```
calcifer.reconciler.devices.reconciled    # Devices that received commands
calcifer.reconciler.devices.skipped       # Devices already converged
calcifer.reconciler.devices.failed        # Failed reconciliations
calcifer.reconciler.devices.no_snapshot   # Data inconsistency (investigate!)
calcifer.reconciler.cycle.duration        # Reconciliation cycle time
```

#### Safety Rules Metrics

```
calcifer.safety.rules.evaluated           # Rules evaluated
calcifer.safety.rules.refused             # Changes blocked by safety
calcifer.safety.rules.modified            # Changes modified by safety
calcifer.safety.rules.accepted            # Changes accepted
calcifer.safety.evaluation.duration       # Evaluation time
```

#### Override Metrics

```
calcifer.overrides.applied                # Overrides applied
calcifer.overrides.blocked                # Overrides blocked by safety
calcifer.overrides.expired                # Overrides expired (TTL)
calcifer.overrides.expiration_cycles      # Expiration job runs
```

#### Infrastructure Metrics

```
calcifer.infrastructure.failures          # Infrastructure failures (by component)
calcifer.infrastructure.status            # Health status (1=healthy, 0=unhealthy)
calcifer.dlq.messages                     # Dead letter queue messages
calcifer.maintenance.stale_devices        # Stale devices detected
calcifer.maintenance.orphans_cleaned      # Orphan index entries cleaned
```

### Prometheus Alerting Rules (Example)

```yaml
groups:
  - name: calcifer
    rules:
      - alert: CalciferReconcilerFailing
        expr: rate(calcifer_reconciler_devices_failed_total[5m]) > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Reconciler failing for some devices"

      - alert: CalciferSafetyRuleActivations
        expr: rate(calcifer_safety_rules_refused_total[5m]) > 1
        for: 5m
        labels:
          severity: info
        annotations:
          summary: "High rate of safety rule activations"

      - alert: CalciferInfrastructureDown
        expr: calcifer_infrastructure_status == 0
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Infrastructure component unhealthy"

      - alert: CalciferDLQMessages
        expr: rate(calcifer_dlq_messages_total[5m]) > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Messages in Dead Letter Queue"
```

### Distributed Tracing

Calcifer uses Micrometer Tracing with OpenTelemetry. Configure your collector:

```yaml
management:
  tracing:
    sampling:
      probability: 0.1  # 10% sampling in production
```

Trace context is propagated across:

- HTTP requests (REST API)
- AMQP messages (RabbitMQ)
- Application events

### Logging

Log format includes correlation ID:

```
[core-server,trace-id,span-id] INFO ...
```

Key log patterns to monitor:

```bash
# Safety rule activations
grep "Safety rule" /var/log/calcifer/app.log

# Infrastructure failures
grep "Infrastructure.*unhealthy" /var/log/calcifer/app.log

# Reconciliation issues
grep "WARN.*Reconciler" /var/log/calcifer/app.log
```

---

## Operational Procedures

### Applying an Override

**Via REST API:**

```bash
# Device override (MAINTENANCE category, 1 hour TTL)
curl -X PUT "http://localhost:8080/api/devices/esp32-001/pump/override/MAINTENANCE" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "RELAY",
    "value": true,
    "reason": "Scheduled maintenance",
    "ttlSeconds": 3600
  }'

# System override (affects all devices in system)
curl -X PUT "http://localhost:8080/api/v1/systems/{systemId}/override/EMERGENCY" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "RELAY",
    "value": false,
    "reason": "Emergency shutdown"
  }'
```

### Cancelling an Override

```bash
# Cancel device override
curl -X DELETE "http://localhost:8080/api/devices/esp32-001/pump/override/MAINTENANCE"

# Cancel system override
curl -X DELETE "http://localhost:8080/api/v1/systems/{systemId}/override/EMERGENCY"
```

### Checking Device State

```bash
# Get device twin snapshot
curl "http://localhost:8080/api/devices/esp32-001/pump/twin"

# Response includes:
# - userIntent: what user requested
# - reportedState: actual device state
# - desiredState: calculated target state
# - isConverged: reported == desired
```

### Creating a FunctionalSystem

```bash
curl -X POST "http://localhost:8080/api/v1/systems" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "TERMOCAMINO",
    "name": "Living Room Fireplace",
    "deviceIds": ["esp32-001:pump", "esp32-001:fire", "esp32-001:fan"],
    "configuration": {
      "mode": "AUTO",
      "targetTemperature": 22.0
    }
  }'
```

### Maintenance Tasks

**Scheduled Jobs (automatic):**

- Override expiration: Every minute (`0 * * * * *`)
- Stale device detection: Daily at 3 AM
- Orphan index cleanup: Daily at 4 AM

**Manual Maintenance:**

```bash
# Check for stale devices (no activity > 7 days)
curl "http://localhost:8080/actuator/metrics/calcifer.maintenance.stale_devices"

# Check DLQ for failed messages
# (Requires RabbitMQ management UI or CLI)
rabbitmqctl list_queues name messages | grep dlq
```

### Troubleshooting

**Device not responding to commands:**

1. Check `/actuator/health` - all components healthy?
2. Check device twin: `GET /api/devices/{id}/twin` - is it converged?
3. Check audit log for safety rule blocks
4. Check DLQ for failed messages

**Safety rule blocking changes:**

1. Query audit log: `db.decision_audit.find({ decisionType: "SAFETY_RULE_ACTIVATED" })`
2. Review rule configuration in `safety-rules.yml`
3. Check if hardcoded rule is triggering (cannot be disabled)

**Override not taking effect:**

1. Check override category precedence
2. Check if higher-category override exists
3. Check if safety rule is modifying/blocking
4. Verify override hasn't expired

