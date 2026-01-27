# ESP32 MQTT Actuator & Sensor Node

A flexible, configuration-driven firmware for ESP32 designed to control actuators and read sensors via MQTT. This project emphasizes **Clean Code** principles, **SOLID** design, and **Data-Driven** configuration, allowing you to define your hardware setup using JSON files without modifying the C++ firmware.

## 🚀 Features

*   **JSON Configuration**: All settings (WiFi, MQTT, Pin Layout) are loaded from the SPIFFS filesystem.
*   **Universal Hardware Support**:
    *   **Digital Input/Output** (with Active Low/Inverted logic support).
    *   **PWM Output** (LED dimming, motor speed).
    *   **Analog Input/Output** (ADC/DAC).
    * **FAN Control** (3-relay discrete speed control with 5 speed states).
    *   **Sensors**: DHT22 (Temp/Hum), DS18B20 (OneWire Temp), MAX6675 (Thermocouple), YL-69 (Soil Moisture).
*   **Robust Connectivity**:
    *   Automatic WiFi reconnection.
    *   Resilient MQTT connection with auto-reconnect logic.
    *   **Watchdog**: Actuators automatically reset to a safe "fallback" state if MQTT communication is lost for a defined interval.
    *   **State Feedback**: Actuators periodically publish their current state to MQTT for monitoring and synchronization.
*   **Testing Tools**: Includes a Python-based mock controller and Dockerized MQTT broker for development.

## 📐 Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                         ESP32 Firmware                              │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│   ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐  │
│   │  WiFiManager │   │  PinConfig   │   │     MqttManager      │  │
│   │              │   │              │   │                      │  │
│   │ • DHCP/Static│   │ • JSON Parse │   │ • Connection Mgmt    │  │
│   │ • Connection │   │ • Validation │   │ • Device Handlers    │  │
│   │              │   │ • Pin Caps   │   │ • Pub/Sub Routing    │  │
│   └──────────────┘   └──────────────┘   └──────────────────────┘  │
│          │                  │                      │               │
│          ▼                  ▼                      ▼               │
│   ┌────────────────────────────────────────────────────────────┐  │
│   │                         SPIFFS                              │  │
│   │  wifi_config.json  pin_config.json  mqtt_config.json       │  │
│   └────────────────────────────────────────────────────────────┘  │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────┐
                    │    MQTT Broker      │
                    └─────────────────────┘
```

## 📂 Project Structure

```text
/
├── data/                   # Configuration JSON files (uploaded to SPIFFS)
├── lib/
│   ├── MqttManager/        # MQTT communication and device handlers
│   ├── PinConfig/          # JSON parsing and pin capability validation
│   └── WiFiManager/        # WiFi connection logic
├── mock-server/            # Python test scripts & Docker environment
├── src/                    # Main entry point
└── platformio.ini          # Project build configuration
```

## 📚 Library Documentation

For detailed technical documentation, see the library-specific READMEs:

| Library | Description | Documentation |
|:--------|:------------|:--------------|
| **PinConfig** | Hardware configuration and ESP32 pin validation | [lib/PinConfig/README.md](lib/PinConfig/README.md) |
| **WiFiManager** | WiFi connection with DHCP/Static IP support | [lib/WiFiManager/README.md](lib/WiFiManager/README.md) |
| **MqttManager** | MQTT communication, device handlers, topic routing | [lib/MqttManager/README.md](lib/MqttManager/README.md) |

For complete MQTT topic patterns and handler details, see: [MQTT Topics Reference](lib/MqttManager/MQTT_TOPICS_REFERENCE.md)

## 🛠️ Setup & Installation

### 1. Prerequisites
*   [VS Code](https://code.visualstudio.com/)
*   [PlatformIO Extension](https://platformio.org/)

### 2. Configuration
Create the following JSON files in the `data/` directory (use the `.template.json` files as a reference):

**`data/wifi_config.json`**
```json
{
  "ssid": "YOUR_SSID",
  "password": "YOUR_PASSWORD",
  "useDhcp": true
}
```

**`data/mqtt_config.json`**
```json
{
  "host": "192.168.1.100",
  "port": 1883,
  "clientId": "ESP32_Room1",
  "username": "user",
  "password": "password",
  "keepAlive": 15
}
```

**`data/pin_config.json`** (Define your hardware here)
```json
[
  {
    "pin": 16,
    "mode": "OUTPUT_DIGITAL",
    "name": "relay-1",
    "defaultState": 0,
    "inverted": true,
    "pollingInterval": 5000
  },
  {
    "pin": 17,
    "mode": "PWM",
    "name": "fan-speed",
    "defaultState": 0,
    "pollingInterval": 10000
  },
  {
    "pin": 21,
    "mode": "DHT22_SENSOR",
    "name": "room-climate",
    "pollingInterval": 2000
  }
]
```

### 3. Uploading
1.  Connect your ESP32 via USB.
2.  **Upload Filesystem Image**: PlatformIO Task -> `Platform` -> `Upload Filesystem Image` (This uploads the JSONs).
3.  **Upload Firmware**: PlatformIO Task -> `General` -> `Upload`.

## 📡 MQTT API

The topic structure is automatically generated based on the `clientId` (from `mqtt_config.json`) and the `name` (from `pin_config.json`).

### Sensors (Producers)

Sensors periodically publish their readings to MQTT topics.

| Mode | Topic Pattern | Payload | Direction |
| :--- | :--- | :--- | :--- |
| **INPUT_DIGITAL** | `/<clientId>/digital_input/<name>/value` | `0` or `1` | Device → MQTT |
| **INPUT_ANALOG** | `/<clientId>/analog_input/<name>/value` | `0` to `4095` | Device → MQTT |
| **DHT22** | `/<clientId>/dht22/<name>/temperature` | Float (e.g., `24.50`) | Device → MQTT |
| **DHT22** | `/<clientId>/dht22/<name>/humidity` | Float (e.g., `50.00`) | Device → MQTT |
| **DS18B20** | `/<clientId>/ds18b20/<name>/temperature` | Float | Device → MQTT |
| **THERMOCOUPLE** | `/<clientId>/thermocouple/<name>/temperature` | Float | Device → MQTT |
| **YL_69_SENSOR** | `/<clientId>/yl69/<name>/value` | `0` to `100` (%) | Device → MQTT |

### Actuators (Consumers + Producers)

Actuators subscribe to command topics (`/set`) AND publish their current state to feedback topics (`/state`).

| Mode               | Topic Pattern                             | Payload                          | Direction     |
|:-------------------|:------------------------------------------|:---------------------------------|:--------------|
| **OUTPUT_DIGITAL** | `/<clientId>/digital_output/<name>/set`   | `0`, `1`, `HIGH`, `LOW`          | MQTT → Device |
| **OUTPUT_DIGITAL** | `/<clientId>/digital_output/<name>/state` | `0`, `1`, `HIGH`, `LOW`          | Device → MQTT |
| **PWM**            | `/<clientId>/pwm/<name>/set`              | `0` to `255`                     | MQTT → Device |
| **PWM**            | `/<clientId>/pwm/<name>/state`            | `0` to `255`                     | Device → MQTT |
| **OUTPUT_ANALOG**  | `/<clientId>/analog_output/<name>/set`    | `0` to `255` (DAC)               | MQTT → Device |
| **OUTPUT_ANALOG**  | `/<clientId>/analog_output/<name>/state`  | `0` to `255` (DAC)               | Device → MQTT |
| **FAN**            | `/<clientId>/fan/<name>/set`              | `0` to `100` (5 discrete states) | MQTT → Device |
| **FAN**            | `/<clientId>/fan/<name>/state`            | `0`, `25`, `50`, `75`, `100`     | Device → MQTT |

### Actuator State Publishing Behavior

Actuators publish their current state to `/state` topics under the following conditions:

| Trigger | Description |
| :--- | :--- |
| **Periodic** | State is published every `pollingInterval` milliseconds |
| **After Command** | State reflects the newly applied value from `/set` topic |
| **After Watchdog Fallback** | When no command is received within `pollingInterval`, the device resets to `defaultState` and publishes this fallback value |

**Example**: For a relay configured with `pollingInterval: 5000`:
- Command received on `/ESP32_Room1/digital_output/relay-1/set` → `1`
- State published on `/ESP32_Room1/digital_output/relay-1/state` → `1`
- If no command for 5 seconds → Watchdog resets to `defaultState`
- State published on `/ESP32_Room1/digital_output/relay-1/state` → `0` (fallback)

> **Note**: State messages are published with the MQTT retain flag enabled.

## ⚙️ Supported Pin Modes

| Mode Name        | Description                                                    | Additional Config Fields                                                 |
|:-----------------|:---------------------------------------------------------------|:-------------------------------------------------------------------------|
| `INPUT_DIGITAL`  | Reads digital state (0/1).                                     | `inverted`, `pollingInterval`                                            |
| `OUTPUT_DIGITAL` | Controls a relay or LED. Publishes state feedback.             | `inverted`, `defaultState`, `pollingInterval`*                           |
| `PWM`            | Pulse Width Modulation output. Publishes state feedback.       | `defaultState`, `pollingInterval`*                                       |
| `INPUT_ANALOG`   | Reads ADC value.                                               | `pollingInterval`                                                        |
| `OUTPUT_ANALOG`  | Writes DAC value (Pins 25, 26 only). Publishes state feedback. | `defaultState`, `pollingInterval`*                                       |
| `DHT22_SENSOR`   | Reads Temp/Humidity.                                           | `pollingInterval`                                                        |
| `DS18B20`        | OneWire Temperature Sensor.                                    | `pollingInterval`                                                        |
| `YL_69_SENSOR`   | Soil Moisture Sensor.                                          | `pollingInterval`                                                        |
| `THERMOCOUPLE`   | MAX6675 SPI Sensor.                                            | `sck`, `so`, `pollingInterval`                                           |
| `FAN`            | 3-relay discrete fan speed control (5 states).                 | `pinRelay2`, `pinRelay3`, `inverted`, `defaultState`, `pollingInterval`* |

> *For actuators, `pollingInterval` controls both the **state publishing interval** and the **watchdog timeout**.

For detailed ESP32 pin capabilities and validation rules, see [PinConfig Documentation](lib/PinConfig/README.md).

## 🔧 Build Configurations & Logging

This project uses a compile-time logging system that can be configured for different build environments.

### Log Levels

| Level | Value | Description |
|:------|:------|:------------|
| `LOG_LEVEL_NONE` | 0 | No logging (maximum performance) |
| `LOG_LEVEL_ERROR` | 1 | Critical errors only |
| `LOG_LEVEL_WARN` | 2 | Errors + warnings |
| `LOG_LEVEL_INFO` | 3 | Errors + warnings + info messages |
| `LOG_LEVEL_DEBUG` | 4 | All messages (default for development) |

### Build Environments

| Environment | Log Level | Use Case |
|:------------|:----------|:---------|
| `az-delivery-devkit-v4` | DEBUG (4) | Development and debugging |
| `production` | ERROR (1) | Production deployment |

### Building

```bash
# Development build (full logging)
pio run -e az-delivery-devkit-v4

# Production build (minimal logging, optimized)
pio run -e production

# Upload development build
pio run -e az-delivery-devkit-v4 --target upload

# Upload production build
pio run -e production --target upload
```

### Performance Impact

Serial logging can significantly impact performance:
- **Blocking I/O**: At 115200 baud, 100 characters take ~8.7ms to transmit
- **CPU Overhead**: String formatting consumes processing cycles
- **Timing Jitter**: Can affect time-sensitive operations

In production builds, all logging calls compile to `((void)0)` (no-op), resulting in **zero runtime overhead**.

## 🧪 Testing & Mock Server

The `mock-server` folder contains a complete environment to test the MQTT interaction without needing external infrastructure.

1.  **Start Broker & Grafana**:
    ```bash
    cd mock-server
    docker-compose up -d
    ```
2.  **Run Control Script**:
    This script simulates a controller that sends commands to the ESP32 in the background.
    ```bash
    pip install -r requirements.txt
    python control_consumers.py
    ```

## 📝 License
This project is open-source. Feel free to modify and adapt it to your needs.
