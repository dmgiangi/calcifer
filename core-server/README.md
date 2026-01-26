# Calcifer Core Server

An IoT device management system that uses the Digital Twin pattern to provide centralized control, monitoring, and
self-healing capabilities for connected devices.

## Overview

The Core Server manages IoT devices through two distinct flows:

- **Input Flow (Sensors)**: Receives and processes data from sensors, such as temperature readings
- **Output Flow (Actuators)**: Controls devices like relays and fans by maintaining desired states

Unlike traditional systems that send direct commands ("turn on now"), Calcifer uses a **desired state** approach. You
tell the system what state you want a device to be in, and the system continuously ensures the device maintains that
state—even if it temporarily loses power or connectivity.

## The Three-State Digital Twin Pattern

Calcifer tracks each controllable device using three separate states:

| State              | Description                                     | Example                               |
|--------------------|-------------------------------------------------|---------------------------------------|
| **User Intent**    | What you want the device to do                  | "I want the fan running at 50% speed" |
| **Reported State** | What the device says it's currently doing       | "The fan is running at 50% speed"     |
| **Desired State**  | The calculated target state the system enforces | "The fan should be at 50% speed"      |

### How It Works

1. **You set an intent**: Through the API, you specify what you want a device to do
2. **System calculates desired state**: Based on your intent (and future business rules), the system determines the
   target state
3. **Device reports its state**: The physical device sends feedback about its current state
4. **System reconciles**: If the reported state doesn't match the desired state, the system automatically sends commands
   to correct it

This approach provides **self-healing** behavior—if a device reboots, loses connection temporarily, or drifts from its
target state, the system automatically brings it back to the desired configuration.

## Supported Devices

### Input Devices (Sensors)

| Device Type            | Description                                            | Data Provided                   |
|------------------------|--------------------------------------------------------|---------------------------------|
| **Temperature Sensor** | Reads temperature from DS18B20 or thermocouple sensors | Temperature value, error status |

### Output Devices (Actuators)

| Device Type | Description                              | Control Values                     |
|-------------|------------------------------------------|------------------------------------|
| **Relay**   | On/Off switching for lights, pumps, etc. | `true` (ON) or `false` (OFF)       |
| **Fan**     | Variable speed control via PWM           | `0` (OFF) to `255` (maximum speed) |

## Device Identification

Each device is identified by a composite key with two parts:

- **Controller ID**: The microcontroller hosting the device (e.g., `esp32-kitchen`)
- **Component ID**: The specific sensor or actuator on that controller (e.g., `main-light`)

The full device identifier uses the format: `controllerId:componentId`

**Examples:**

- `esp32-kitchen:main-light` — A relay controlling the kitchen's main light
- `esp32-garage:exhaust-fan` — A fan in the garage
- `esp32-greenhouse:ds18b20` — A temperature sensor in the greenhouse

## Self-Healing Reconciliation

The Core Server runs a background reconciliation process that:

1. Periodically queries all active output devices (default: every 5 seconds)
2. For each device, sends commands to ensure it matches the desired state
3. Logs any failures for troubleshooting

This means devices automatically recover from:

- Power outages or reboots
- Temporary network disconnections
- Manual overrides at the hardware level

## Prerequisites

| Requirement  | Version | Purpose                                             |
|--------------|---------|-----------------------------------------------------|
| **Java**     | 25      | Runtime environment                                 |
| **Redis**    | 6.x+    | State persistence for device twins                  |
| **RabbitMQ** | 3.x+    | Message broker for device communication (AMQP/MQTT) |

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

### RabbitMQ Connection

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: admin
    password: your_password
```

### Reconciliation Interval

```yaml
app:
  iot:
    polling-interval-ms: 5000  # Check devices every 5 seconds
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

### Submit a User Intent

Set the desired state for a device:

```bash
# Turn on a relay
curl -X POST http://localhost:8080/devices/esp32-kitchen/main-light/intent \
  -H "Content-Type: application/json" \
  -d '{"type": "RELAY", "value": true}'

# Set fan speed to 128 (50%)
curl -X POST http://localhost:8080/devices/esp32-garage/exhaust-fan/intent \
  -H "Content-Type: application/json" \
  -d '{"type": "FAN", "value": 128}'
```

### Check Device Twin Status

View the complete state of a device (intent, reported, and desired):

```bash
curl http://localhost:8080/devices/esp32-kitchen/main-light/twin
```

## Example Use Cases

### Smart Home Lighting

Control lights throughout your home with automatic recovery. If a smart relay loses power and reboots, it automatically
returns to its last desired state (on or off).

### HVAC Fan Control

Manage ventilation fans with variable speed control. Set a fan to run at 30% during the day and 70% at night, with the
system ensuring consistent operation.

### Greenhouse Monitoring

Collect temperature readings from multiple sensors across a greenhouse. Use the data to trigger automated responses (
future feature) or monitor conditions remotely.

### Workshop Safety

Control exhaust fans and dust collection systems. The self-healing behavior ensures safety equipment stays operational
even after brief power interruptions.

