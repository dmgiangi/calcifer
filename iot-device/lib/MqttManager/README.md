---
title: "MqttManager Library"
subtitle: "MQTT Communication for ESP32 IoT Devices"
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

# MqttManager Library

Manages MQTT-based communication for ESP32 IoT devices, providing automatic topic generation and message handling for sensors and actuators.

## Overview

The MqttManager uses a **Singleton pattern** with a **Strategy-based handler architecture** where each device type has a dedicated handler implementing the `IDeviceHandler` interface. Handlers are registered with the `DeviceHandlerRegistry` and automatically invoked during initialization.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      MqttManager                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  Producers  │  │  Consumers  │  │  PubSubClient       │  │
│  │  (sensors)  │  │ (actuators) │  │  (MQTT connection)  │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└───────────────────────────┬─────────────────────────────────┘
                            │
              ┌─────────────▼─────────────┐
              │  DeviceHandlerRegistry    │
              └─────────────┬─────────────┘
                            │
    ┌───────────────────────┼───────────────────────┐
    │           │           │           │           │
    ▼           ▼           ▼           ▼           ▼
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│Digital │ │Digital │ │  PWM   │ │ DHT22  │ │  ...   │
│ Input  │ │ Output │ │Handler │ │Handler │ │        │
└────────┘ └────────┘ └────────┘ └────────┘ └────────┘
```

**Handlers:**
- `DigitalInputHandler` (Sensor → Producer)
- `DigitalOutputHandler` (Actuator → Consumer + Producer)
- `PwmHandler` (Actuator → Consumer + Producer)
- `AnalogInputHandler` (Sensor → Producer)
- `AnalogOutputHandler` (Actuator → Consumer + Producer)
- `Dht22Handler` (Sensor → Producer)
- `Yl69Handler` (Sensor → Producer)
- `Ds18b20Handler` (Sensor → Producer)
- `ThermocoupleHandler` (Sensor → Producer)
- `FanHandler` (Actuator → Consumer + Producer) - 3-relay discrete speed control

## MQTT Configuration

### JSON Schema (`mqtt_config.json`)

```json
{
  "host": "192.168.1.100",
  "port": 1883,
  "clientId": "ESP32_Room1",
  "username": "user",
  "password": "secret",
  "keepAlive": 15
}
```

### Field Reference

| Field | Type | Required | Default | Description |
|:------|:-----|:--------:|:-------:|:------------|
| `host` | string | ✓ | - | MQTT broker hostname or IP |
| `port` | int | - | `1883` | MQTT broker port |
| `clientId` | string | - | `"ESP32Client"` | Unique client identifier (used in topics) |
| `username` | string | - | `""` | Broker authentication username |
| `password` | string | - | `""` | Broker authentication password |
| `keepAlive` | int | - | `15` | Keep-alive interval in seconds |

## Key Concepts

### Producers
Sensors that periodically read data and publish to MQTT topics.
- Publishing interval controlled by `pollingInterval`
- Topic pattern: `/<clientId>/<device_type>/<name>/value`

### Consumers
Actuators that subscribe to MQTT topics and execute commands.
- Watchdog timeout controlled by `pollingInterval`
- Command topic pattern: `/<clientId>/<device_type>/<name>/set`

### Consumer + Producer (Actuators with State Feedback)
Actuators that both receive commands AND publish their current state:
- Subscribe to `/set` topic for commands
- Publish to `/state` topic for state feedback
- State is published every `pollingInterval` milliseconds
- State messages are published with MQTT retain flag

## Supported Pin Modes

The manager automatically generates topics based on the `clientId` and `PinConfig.name`.

### Sensors (Pure Producers)

| Mode | Topic Format | Description |
| :--- | :--- | :--- |
| `INPUT_DIGITAL` | `.../digital_input/{name}/value` | Publishes `0` or `1`. |
| `INPUT_ANALOG` | `.../analog_input/{name}/value` | Publishes ADC value (12-bit). |
| `DHT22_SENSOR` | `.../dht22/{name}/temperature`<br>`.../dht22/{name}/humidity` | Publishes Temp and Hum. |
| `YL_69_SENSOR` | `.../yl69/{name}/value` | Publishes moisture percentage. |
| `DS18B20` | `.../ds18b20/{name}/temperature` | Publishes temperature (°C). |
| `THERMOCOUPLE` | `.../thermocouple/{name}/temperature` | Publishes temperature (°C). |

### Actuators (Consumer + Producer)

Actuators subscribe to `/set` topics for commands AND publish their current state to `/state` topics.

| Mode             | Command Topic                   | State Topic                       | Payload                         |
|:-----------------|:--------------------------------|:----------------------------------|:--------------------------------|
| `OUTPUT_DIGITAL` | `.../digital_output/{name}/set` | `.../digital_output/{name}/state` | `0`/`1` or `LOW`/`HIGH`         |
| `PWM`            | `.../pwm/{name}/set`            | `.../pwm/{name}/state`            | Duty cycle `0-255`              |
| `OUTPUT_ANALOG`  | `.../analog_output/{name}/set`  | `.../analog_output/{name}/state`  | DAC value `0-255`               |
| `FAN`            | `.../fan/{name}/set`            | `.../fan/{name}/state`            | Speed `0-4` (5 discrete states) |

**State Publishing Behavior:**
- State is published every `pollingInterval` milliseconds
- State reflects the current value (from command or watchdog fallback)
- Messages are published with the MQTT retain flag enabled

## API Reference

### `loadConfig(filename)`

Loads MQTT broker configuration from a JSON file.

```cpp
static bool loadConfig(const char *filename);
```

**Returns:** `true` if configuration loaded successfully, `false` if file missing or invalid.

### `registerPins(configs)`

Registers pin configurations with the handler registry. Creates producers and consumers based on device type.

```cpp
static bool registerPins(const std::vector<PinConfig> &configs);
```

**Parameters:**
- `configs`: Vector of `PinConfig` objects from `loadConfiguration()`

**Returns:** `true` on success.

### `connect(client)`

Initializes the MQTT connection using the provided PubSubClient.

```cpp
static bool connect(PubSubClient &client);
```

**Parameters:**
- `client`: Reference to a `PubSubClient` instance

**Returns:** `true` if initial connection succeeds.

### `loop()`

Main loop function - handles reconnection (every 5 seconds if disconnected) and calls `PubSubClient::loop()`.

```cpp
static void loop();
```

### `handleProducers()`

Publishes sensor data for all producers whose interval has elapsed. Messages are published with the MQTT retain flag.

```cpp
static void handleProducers();
```

### `handleConsumers()`

Implements watchdog behavior - resets actuators to `defaultState` if no message received within `pollingInterval`.

```cpp
static void handleConsumers();
```

## Usage Example

```cpp
#include <WiFi.h>
#include <PubSubClient.h>
#include <SPIFFS.h>
#include <PinConfig.h>
#include "MqttManager.h"

WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);

void setup() {
    Serial.begin(115200);
    SPIFFS.begin(true);

    // Connect WiFi first...

    // Load configurations
    MqttManager::loadConfig("/mqtt_config.json");
    std::vector<PinConfig> configs = loadConfiguration("/pin_config.json");
    MqttManager::registerPins(configs);

    // Connect to MQTT broker
    MqttManager::connect(mqttClient);
}

void loop() {
    MqttManager::loop();
    MqttManager::handleProducers();
    MqttManager::handleConsumers();
}
```

## Reconnection & Watchdog Behavior

**Reconnection:**
- If disconnected, attempts reconnection every **5 seconds**
- On reconnection, automatically re-subscribes to all consumer topics

**Watchdog:**
- Each consumer has a watchdog timer based on `pollingInterval`
- If no command received within interval, actuator resets to `defaultState`
- Watchdog timer resets on each valid message

## File Structure

```
lib/MqttManager/
├── MqttManager.h/.cpp       # Main manager class
├── README.md                # This file
├── MQTT_TOPICS_REFERENCE.md # Detailed handler documentation
└── handlers/
    ├── IDeviceHandler.h     # Base interface
    ├── DeviceHandlerRegistry.h/.cpp
    ├── DigitalInputHandler.h/.cpp
    ├── DigitalOutputHandler.h/.cpp
    ├── PwmHandler.h/.cpp
    ├── AnalogInputHandler.h/.cpp
    ├── AnalogOutputHandler.h/.cpp
    ├── Dht22Handler.h/.cpp
    ├── Yl69Handler.h/.cpp
    ├── Ds18b20Handler.h/.cpp
    ├── ThermocoupleHandler.h/.cpp
    └── FanHandler.h/.cpp    # 3-relay fan speed control
```

## See Also

- **[MQTT_TOPICS_REFERENCE.md](./MQTT_TOPICS_REFERENCE.md)** - Complete handler documentation with topic patterns, payload formats, and detailed examples for each device type

