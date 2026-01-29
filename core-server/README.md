---
title: "Calcifer Core Server"
subtitle: "IoT Device Management with Digital Twin Pattern"
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

# Calcifer Core Server

An IoT device management system that uses the Digital Twin pattern to provide centralized control, monitoring, and
self-healing capabilities for connected devices. Built with **Spring Boot 4.0.2** and **Java 25**.

## Overview

The Core Server manages IoT devices through two distinct flows:

- **Input Flow (Sensors)**: Receives and processes data from sensors, such as temperature readings
- **Output Flow (Actuators)**: Controls devices like relays and fans by maintaining desired states

Unlike traditional systems that send direct commands ("turn on now"), Calcifer uses a **desired state** approach. You
tell the system what state you want a device to be in, and the system continuously ensures the device maintains that
state—even if it temporarily loses power or connectivity.

## Key Features

| Feature                         | Description                                                          |
|---------------------------------|----------------------------------------------------------------------|
| **Three-State Digital Twin**    | Tracks Intent, Reported, and Desired states for each device          |
| **FunctionalSystem Aggregates** | Group devices into logical systems (e.g., Termocamino, HVAC)         |
| **Safety Rules Engine**         | Hardcoded + configurable rules with SpEL expressions                 |
| **Override Management**         | Categorized overrides (EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL) |
| **Event-Driven Reconciliation** | Immediate command dispatch with 50ms debounce                        |
| **Self-Healing**                | Automatic recovery from power outages and network issues             |
| **Observability**               | Micrometer metrics, distributed tracing, audit logging               |

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Infrastructure Layer                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │ REST API     │  │ WebSocket    │  │ AMQP/MQTT    │  │ Persistence      │ │
│  │ Controllers  │  │ STOMP        │  │ Flows        │  │ Redis + MongoDB  │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘ │
├─────────┼─────────────────┼─────────────────┼───────────────────┼───────────┤
│         │          Application Layer        │                   │           │
│         ▼                 ▼                 ▼                   ▼           │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  OverrideApplicationService  │  OverrideValidationPipeline              ││
│  └─────────────────────────────────────────────────────────────────────────┘│
├─────────────────────────────────────────────────────────────────────────────┤
│                            Domain Layer                                      │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  StateCalculator  │  SafetyValidator  │  ReconciliationCoordinator      ││
│  │  SafetyRuleEngine │  DeviceSystemMappingService                         ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  FunctionalSystem (Aggregate)  │  DeviceTwinSnapshot  │  Override       ││
│  │  UserIntent  │  ReportedDeviceState  │  DesiredDeviceState              ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
```

## The Three-State Digital Twin Pattern

Calcifer tracks each controllable device using three separate states:

| State              | Description                                     | Example                             |
|--------------------|-------------------------------------------------|-------------------------------------|
| **User Intent**    | What you want the device to do                  | "I want the fan running at speed 2" |
| **Reported State** | What the device says it's currently doing       | "The fan is running at speed 2"     |
| **Desired State**  | The calculated target state the system enforces | "The fan should be at speed 2"      |

### State Calculation Pipeline

```
User Intent → Override Resolution → Safety Validation → Desired State
                    ↓                      ↓
            (if override active)    (may modify/refuse)
```

1. **You set an intent**: Through the API, you specify what you want a device to do
2. **Override resolution**: System checks for active overrides (EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL)
3. **Safety validation**: Safety rules validate and may modify the proposed value
4. **Desired state calculated**: Final target state is persisted and commands are dispatched
5. **Device reports its state**: The physical device sends feedback about its current state
6. **System reconciles**: If reported ≠ desired, the system automatically sends correction commands

This approach provides **self-healing** behavior—if a device reboots, loses connection temporarily, or drifts from its
target state, the system automatically brings it back to the desired configuration.

## Supported Devices

### Input Devices (Sensors)

| Device Type            | Description                                            | Data Provided                   |
|------------------------|--------------------------------------------------------|---------------------------------|
| **Temperature Sensor** | Reads temperature from DS18B20 or thermocouple sensors | Temperature value, error status |

### Output Devices (Actuators)

| Device Type | Description                              | Control Values                   |
|-------------|------------------------------------------|----------------------------------|
| **Relay**   | On/Off switching for lights, pumps, etc. | `true` (ON) or `false` (OFF)     |
| **Fan**     | Variable speed control via PWM           | `0` (OFF) to `4` (maximum speed) |

## Device Identification

Each device is identified by a composite key with two parts:

- **Controller ID**: The microcontroller hosting the device (e.g., `esp32-kitchen`)
- **Component ID**: The specific sensor or actuator on that controller (e.g., `main-light`)

The full device identifier uses the format: `controllerId:componentId`

**Examples:**

- `esp32-kitchen:main-light` — A relay controlling the kitchen's main light
- `esp32-garage:exhaust-fan` — A fan in the garage
- `esp32-greenhouse:ds18b20` — A temperature sensor in the greenhouse

## FunctionalSystem Aggregates

Devices can be grouped into **FunctionalSystems** - logical aggregates that represent real-world systems:

| System Type     | Description                                | Safety Interlocks    |
|-----------------|--------------------------------------------|----------------------|
| **TERMOCAMINO** | Wood-burning fireplace with water heating  | Critical (fire-pump) |
| **HVAC**        | Heating, Ventilation, and Air Conditioning | Temperature-based    |
| **IRRIGATION**  | Garden/agricultural irrigation system      | Water-related        |
| **GENERIC**     | Custom system without predefined rules     | None                 |

### Termocamino Safety Example

The Termocamino system has critical safety interlocks:

- **Fire-Pump Interlock**: When fire is ON, pump MUST be ON (prevents overheating)
- **Pump-Fire Interlock**: Cannot turn fire OFF while pump is ON (prevents thermal runaway)

These are **hardcoded safety rules** that cannot be overridden.

## Override Management

Overrides allow temporary or permanent changes to device behavior, organized by category:

| Category        | Priority | Use Case                         |
|-----------------|----------|----------------------------------|
| **EMERGENCY**   | Highest  | Critical safety situations       |
| **MAINTENANCE** | High     | Scheduled maintenance windows    |
| **SCHEDULED**   | Medium   | Time-based automation            |
| **MANUAL**      | Lowest   | User-initiated temporary changes |

**Conflict Resolution:**

- Higher category always wins
- Same category: device-level override wins over system-level
- Same category + scope: most recent wins

## Safety Rules Engine

The system uses a layered safety approach:

1. **Hardcoded Rules** (Java): Critical safety interlocks that cannot be bypassed
2. **Configurable Rules** (YAML + SpEL): Operator-defined rules loaded at startup

See `safety-rules.yml` for configurable rule examples.

## Self-Healing Reconciliation

The Core Server runs **two reconciliation processes**:

### Immediate Reconciler (Event-Driven)

- Triggers on `DesiredStateCalculatedEvent`
- 50ms debounce window per device
- Sends commands immediately after state calculation

### Scheduled Reconciler (Drift Detection)

- Runs every 5 seconds (configurable)
- Detects and corrects state drift
- Health check for device convergence

This means devices automatically recover from:

- Power outages or reboots
- Temporary network disconnections
- Manual overrides at the hardware level

## Prerequisites

| Requirement  | Version | Purpose                                               |
|--------------|---------|-------------------------------------------------------|
| **Java**     | 25      | Runtime environment                                   |
| **Redis**    | 7.x+    | Real-time state persistence for device twins          |
| **MongoDB**  | 7.x+    | Configuration persistence (systems, overrides, audit) |
| **RabbitMQ** | 3.x+    | Message broker for device communication (AMQP/MQTT)   |

## Configuration

The server is configured via `application.yaml`. Key settings include:

### Redis Connection

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      username: your_username
      password: your_password
      database: 0
```

### MongoDB Connection

```yaml
spring:
  data:
    mongodb:
      host: localhost
      port: 27017
      database: calcifer
      username: your_username
      password: your_password
      auto-index-creation: true
```

### RabbitMQ Connection

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: admin
    password: your_password
```

### Reconciliation Settings

```yaml
app:
  iot:
    polling-interval-ms: 5000  # Scheduled reconciler interval (drift detection)
  reconciliation:
    debounce-ms: 50            # Immediate reconciler debounce window
  override:
    expiration-cron: "0 * * * * *"  # Check for expired overrides every minute
  maintenance:
    stale-detection-cron: "0 0 3 * * *"  # Detect stale devices at 3 AM
    orphan-cleanup-cron: "0 0 4 * * *"   # Clean orphan index entries at 4 AM
```

## Getting Started

### 1. Start Required Services

Ensure Redis and RabbitMQ are running and accessible.

### 2. Configure the Application

Copy `application-dev.yaml` as a template and adjust the connection settings for your environment.

### 3. Run the Server

```bash
# Using Maven
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Or with Java directly
java -jar core-server.jar --spring.profiles.active=dev
```

### 4. Verify the Server is Running

The server exposes Spring Boot Actuator endpoints for health monitoring:

```
GET http://localhost:8080/actuator/health
```

## API Usage

### Device Intent API

```bash
# Submit a user intent (turn on a relay)
curl -X POST http://localhost:8080/api/devices/esp32-kitchen/main-light/intent \
  -H "Content-Type: application/json" \
  -d '{"type": "RELAY", "value": true}'

# Set fan speed to 2 (range: 0-4)
curl -X POST http://localhost:8080/api/devices/esp32-garage/exhaust-fan/intent \
  -H "Content-Type: application/json" \
  -d '{"type": "FAN", "value": 2}'

# Get device twin snapshot
curl http://localhost:8080/api/devices/esp32-kitchen/main-light/twin
```

### Override API

```bash
# Apply a device override (MANUAL category)
curl -X PUT http://localhost:8080/api/devices/esp32-kitchen/main-light/override/MANUAL \
  -H "Content-Type: application/json" \
  -d '{"value": {"state": true}, "reason": "Testing", "expiresAt": "2026-01-30T12:00:00Z"}'

# Cancel a device override
curl -X DELETE http://localhost:8080/api/devices/esp32-kitchen/main-light/override/MANUAL

# Apply a system override (affects all devices in system)
curl -X PUT http://localhost:8080/api/v1/systems/{systemId}/override/MAINTENANCE \
  -H "Content-Type: application/json" \
  -d '{"value": {"state": false}, "reason": "Scheduled maintenance"}'
```

### FunctionalSystem API

```bash
# List all systems
curl http://localhost:8080/api/v1/systems

# Get system details with device states
curl http://localhost:8080/api/v1/systems/{systemId}

# Update system configuration
curl -X PATCH http://localhost:8080/api/v1/systems/{systemId}/configuration \
  -H "Content-Type: application/json" \
  -d '{"mode": "AUTO", "targetTemperature": 22.5}'
```

### WebSocket (Real-time Updates)

Connect via STOMP over SockJS:

```javascript
const socket = new SockJS('/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, () => {
    // Subscribe to device updates
    stompClient.subscribe('/topic/devices/esp32-kitchen:main-light', (message) => {
        console.log('Device update:', JSON.parse(message.body));
    });

    // Subscribe to system updates
    stompClient.subscribe('/topic/systems/{systemId}', (message) => {
        console.log('System update:', JSON.parse(message.body));
    });
});
```

## Observability

### Health Endpoints

```bash
# Liveness probe (is the app running?)
curl http://localhost:8080/actuator/health/liveness

# Readiness probe (can the app serve traffic?)
curl http://localhost:8080/actuator/health/readiness

# Full health details
curl http://localhost:8080/actuator/health
```

### Prometheus Metrics

```bash
curl http://localhost:8080/actuator/prometheus
```

Key metrics:

- `calcifer_reconciler_devices_reconciled_total` - Devices that received commands
- `calcifer_reconciler_devices_skipped_total` - Devices skipped (already converged)
- `calcifer_safety_rules_refused_total` - Safety rule refusals
- `calcifer_overrides_applied_total` - Overrides applied
- `calcifer_dlq_messages_total` - Dead letter queue messages

### Distributed Tracing

The system uses Micrometer Tracing with OpenTelemetry. Correlation IDs are automatically propagated across:

- HTTP requests
- AMQP messages
- Application events

## Example Use Cases

### Termocamino (Wood-Burning Fireplace)

A complete heating system with critical safety interlocks:

- Fire relay controls the combustion
- Pump relay circulates water to radiators
- Fan controls air flow
- Temperature sensors monitor water and flue temperatures

Safety rules ensure the pump is always ON when fire is ON, preventing overheating.

### Smart Home Lighting

Control lights throughout your home with automatic recovery. If a smart relay loses power and reboots, it automatically
returns to its last desired state (on or off).

### HVAC Fan Control

Manage ventilation fans with variable speed control. Set a fan to run at speed 2 during the day and speed 4 at night,
with the system ensuring consistent operation.

### Greenhouse Monitoring

Collect temperature readings from multiple sensors across a greenhouse. Use the data to trigger automated responses
via configurable safety rules.

## Further Documentation

- **[CUSTOMIZATION.md](CUSTOMIZATION.md)** - Developer guide for extending the system
- **[DOMAIN.md](DOMAIN.md)** - Domain model documentation
- **[safety-rules.yml](src/main/resources/safety-rules.yml)** - Configurable safety rules examples
