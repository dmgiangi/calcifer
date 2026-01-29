---
title: "MQTT Topics Reference"
subtitle: "Complete Reference for Device Handlers and Topic Patterns"
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

# MQTT Topics Reference

Complete reference for all device handlers in the MqttManager library. Each handler manages a specific device type and automatically generates MQTT topics based on the `clientId` and device `name`.

## 📋 Quick Reference Table

| Handler                | Device Type | Role                | Topic Pattern                                        | Payload                 |
|:-----------------------|:------------|:--------------------|:-----------------------------------------------------|:------------------------|
| `DigitalInputHandler`  | Sensor      | Producer            | `/<clientId>/digital_input/<name>/value`             | `0` or `1`              |
| `DigitalOutputHandler` | Actuator    | Consumer + Producer | `/<clientId>/digital_output/<name>/set` (cmd)        | `0`, `1`, `HIGH`, `LOW` |
|                        |             |                     | `/<clientId>/digital_output/<name>/state` (feedback) | `0`, `1`, `HIGH`, `LOW` |
| `PwmHandler`           | Actuator    | Consumer + Producer | `/<clientId>/pwm/<name>/set` (cmd)                   | `0` - `255`             |
|                        |             |                     | `/<clientId>/pwm/<name>/state` (feedback)            | `0` - `255`             |
| `AnalogInputHandler`   | Sensor      | Producer            | `/<clientId>/analog_input/<name>/value`              | `0` - `4095`            |
| `AnalogOutputHandler`  | Actuator    | Consumer + Producer | `/<clientId>/analog_output/<name>/set` (cmd)         | `0` - `255`             |
|                        |             |                     | `/<clientId>/analog_output/<name>/state` (feedback)  | `0` - `255`             |
| `FanHandler`           | Actuator    | Consumer + Producer | `/<clientId>/fan/<name>/set` (cmd)                   | `0` - `4`               |
|                        |             |                     | `/<clientId>/fan/<name>/state` (feedback)            | `0` - `4`               |
| `Dht22Handler`         | Sensor      | Producer            | `/<clientId>/dht22/<name>/temperature`               | Float (°C)              |
|                        |             |                     | `/<clientId>/dht22/<name>/humidity`                  | Float (%)               |
| `Yl69Handler`          | Sensor      | Producer            | `/<clientId>/yl69/<name>/value`                      | `0` - `100` (%)         |
| `Ds18b20Handler`       | Sensor      | Producer            | `/<clientId>/ds18b20/<name>/temperature`             | Float (°C)              |
| `ThermocoupleHandler`  | Sensor      | Producer            | `/<clientId>/thermocouple/<name>/temperature`        | Float (°C)              |

### Legend

- **Producer**: Publishes sensor data to MQTT at regular intervals (`pollingInterval`)
- **Consumer**: Subscribes to MQTT topic and executes commands on the actuator
- **Consumer + Producer**: Subscribes to commands (`/set`) AND publishes current state (`/state`)
- **`<clientId>`**: Value from `mqtt_config.json` → `clientId` field
- **`<name>`**: Value from `pin_config.json` → `name` field for each device

---

## 📖 Detailed Handler Documentation

---

### DigitalInputHandler

**File**: `handlers/DigitalInputHandler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Sensor (Button, Switch, Door Sensor) |
| **MQTT Role** | Producer |
| **Topic** | `/<clientId>/digital_input/<name>/value` |
| **Payload Format** | `0` (LOW) or `1` (HIGH) |
| **Publish Trigger** | Every `pollingInterval` ms |

#### Notes
- Pin is configured with `INPUT_PULLUP`
- When `inverted: true`, logical `1` is published when physical pin is LOW

---

### DigitalOutputHandler

**File**: `handlers/DigitalOutputHandler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Actuator (Relay, LED, Solenoid) |
| **MQTT Role** | Consumer + Producer |
| **Command Topic** | `/<clientId>/digital_output/<name>/set` |
| **State Topic** | `/<clientId>/digital_output/<name>/state` |
| **Payload Format** | `0`, `1`, `LOW`, `HIGH` |
| **State Publishing** | Every `pollingInterval` ms |
| **Watchdog** | Resets to `defaultState` after `pollingInterval` ms without message |

#### MQTT Examples

**Command (MQTT → Device):**
```
Topic:   /ESP32_Room1/digital_output/status-led/set
Payload: 1
```

**State Feedback (Device → MQTT):**
```
Topic:   /ESP32_Room1/digital_output/status-led/state
Payload: 1
```

#### Notes
- Accepts both numeric (`0`, `1`) and string (`HIGH`, `LOW`) payloads
- When `inverted: true`, sending `1` sets the physical pin to LOW
- **State Publishing**: Current state is published to `/state` topic every `pollingInterval` ms
- **Watchdog**: If no message received within `pollingInterval`, pin resets to `defaultState`

---

### PwmHandler

**File**: `handlers/PwmHandler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Actuator (LED Dimmer, Motor Speed, Servo) |
| **MQTT Role** | Consumer + Producer |
| **Command Topic** | `/<clientId>/pwm/<name>/set` |
| **State Topic** | `/<clientId>/pwm/<name>/state` |
| **Payload Format** | Integer `0` - `255` (duty cycle) |
| **PWM Frequency** | 5000 Hz |
| **Resolution** | 8-bit (256 levels) |

#### MQTT Examples

**Command (MQTT → Device):**
```
Topic:   /ESP32_Room1/pwm/fan-speed/set
Payload: 128
```

**State Feedback (Device → MQTT):**
```
Topic:   /ESP32_Room1/pwm/fan-speed/state
Payload: 128
```

#### Notes
- ESP32 supports up to **16 PWM channels** (shared across all PWM devices)
- Values outside 0-255 are automatically constrained
- **State Publishing**: Current duty cycle is published to `/state` topic every `pollingInterval` ms
- **Watchdog**: Resets to `defaultState` if no message received within `pollingInterval`

---

### AnalogInputHandler

**File**: `handlers/AnalogInputHandler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Sensor (Potentiometer, Light Sensor, Generic ADC) |
| **MQTT Role** | Producer |
| **Topic** | `/<clientId>/analog_input/<name>/value` |
| **Payload Format** | Integer `0` - `4095` (12-bit ADC) |

#### Notes
- Only ADC-capable pins: GPIO 32-39 (ADC1), GPIO 0, 2, 4, 12-15, 25-27 (ADC2)
- ADC2 pins cannot be used when WiFi is active

---

### AnalogOutputHandler

**File**: `handlers/AnalogOutputHandler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Actuator (Analog Voltage Output) |
| **MQTT Role** | Consumer + Producer |
| **Command Topic** | `/<clientId>/analog_output/<name>/set` |
| **State Topic** | `/<clientId>/analog_output/<name>/state` |
| **Payload Format** | Integer `0` - `255` (8-bit DAC) |
| **Output Voltage** | 0V - 3.3V |

#### Notes
- **Only GPIO 25 and 26** support DAC on ESP32
- Output voltage: `value / 255 * 3.3V` (e.g., 128 → ~1.65V)
- **State Publishing**: Current DAC value is published to `/state` topic every `pollingInterval` ms

---

### Dht22Handler

**File**: `handlers/Dht22Handler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Sensor (Temperature & Humidity) |
| **MQTT Role** | Producer (2 topics) |
| **Topics** | `/<clientId>/dht22/<name>/temperature`<br>`/<clientId>/dht22/<name>/humidity` |
| **Payload Format** | Float with 2 decimal places |

#### Notes
- ⚠️ **Publishes to 2 separate topics** (temperature and humidity)
- Minimum recommended `pollingInterval`: 2000ms (sensor limitation)
- Returns `"nan"` if sensor read fails

---

### Yl69Handler

**File**: `handlers/Yl69Handler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Sensor (Soil Moisture) |
| **MQTT Role** | Producer |
| **Topic** | `/<clientId>/yl69/<name>/value` |
| **Payload Format** | Integer `0` - `100` (percentage) |

#### Notes
- Raw ADC value (0-4095) is converted to percentage (0-100%)
- **Inverted mapping**: High ADC = Dry (0%), Low ADC = Wet (100%)

---

### Ds18b20Handler

**File**: `handlers/Ds18b20Handler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Sensor (Temperature, OneWire) |
| **MQTT Role** | Producer |
| **Topic** | `/<clientId>/ds18b20/<name>/temperature` |
| **Payload Format** | Float with 2 decimal places (°C) |

#### Notes
- Uses OneWire protocol (requires 4.7kΩ pull-up resistor)
- Reads first sensor on the bus (`getTempCByIndex(0)`)
- Returns `"error"` if sensor disconnected

---

### ThermocoupleHandler

**File**: `handlers/ThermocoupleHandler.cpp`

| Property | Value |
|:---------|:------|
| **Device Type** | Sensor (High-Temperature, MAX6675) |
| **MQTT Role** | Producer |
| **Topic** | `/<clientId>/thermocouple/<name>/temperature` |
| **Payload Format** | Float with 2 decimal places (°C) |
| **Temperature Range** | 0°C to 1024°C |

#### Notes
- ⚠️ **Requires 3 pins**: CS (`pin`), SCK (`pinClock`), and SO (`pinData`)
- Uses MAX6675 chip with K-type thermocouple
- Resolution: 0.25°C

---

### FanHandler

**File**: `handlers/FanHandler.cpp`

| Property           | Value                                   |
|:-------------------|:----------------------------------------|
| **Device Type**    | Actuator (3-Relay Fan Speed Controller) |
| **MQTT Role**      | Consumer + Producer                     |
| **Command Topic**  | `/<clientId>/fan/<name>/set`            |
| **State Topic**    | `/<clientId>/fan/<name>/state`          |
| **Payload Format** | Integer `0` - `4` (5 discrete states)   |
| **Hardware**       | 3 Relays for discrete speed control     |

#### Pin Configuration

| JSON Field  | Description          | Requirements           |
|:------------|:---------------------|:-----------------------|
| `pin`       | Relay 1 control GPIO | Digital output capable |
| `pinRelay2` | Relay 2 control GPIO | Digital output capable |
| `pinRelay3` | Relay 3 control GPIO | Digital output capable |

#### MQTT Examples

**Command (MQTT → Device):**
```
Topic:   /ESP32_Room1/fan/ceiling-fan/set
Payload: 3
```

**State Feedback (Device → MQTT):**
```
Topic:   /ESP32_Room1/fan/ceiling-fan/state
Payload: 3
```

#### Behavior

The fan uses 3 relays to provide 5 discrete speed states:

| State | Relay 1 | Relay 2 | Relay 3 | Description       |
|:-----:|:-------:|:-------:|:-------:|:------------------|
|   0   |   OFF   |   OFF   |   OFF   | Fan stopped       |
|   1   |   ON    |   OFF   |   OFF   | Lowest speed      |
|   2   |   OFF   |   ON    |   OFF   | Medium-low speed  |
|   3   |   ON    |   ON    |   OFF   | Medium-high speed |
|   4   |   OFF   |   OFF   |   ON    | Highest speed     |

#### MQTT API

| MQTT Value | State | State Feedback |
|:----------:|:-----:|:--------------:|
|    `0`     |   0   |      `0`       |
|    `1`     |   1   |      `1`       |
|    `2`     |   2   |      `2`       |
|    `3`     |   3   |      `3`       |
|    `4`     |   4   |      `4`       |

Values outside 0-4 are constrained (negative → 0, >4 → 4).

#### Kickstart Feature

The kickstart feature helps motors start reliably at lower speeds by applying full power briefly before switching to the
target speed.

| Field               | Type | Default | Description                                   |
|:--------------------|:-----|:-------:|:----------------------------------------------|
| `kickstartEnabled`  | bool | `false` | Enable/disable the kickstart feature          |
| `kickstartDuration` | int  |   `0`   | Duration in ms to apply full power at startup |

**Kickstart Behavior:**

- When transitioning from **OFF (state 0)** to **states 1, 2, or 3**: Apply full power (state 4) for `kickstartDuration`
  ms, then switch to the requested speed
- When transitioning from **OFF to state 4**: No kickstart needed (already at full power)
- When transitioning **between non-zero states** (e.g., 2→3): No kickstart (motor already running)
- If `kickstartEnabled` is `false` or `kickstartDuration` is `0`: Normal behavior (no kickstart)

#### JSON Configuration Example

```json
{
  "pin": 26,
  "pinRelay2": 25,
  "pinRelay3": 14,
  "mode": "FAN",
  "name": "ceiling-fan",
  "defaultState": 0,
  "pollingInterval": 30000,
  "inverted": true,
  "kickstartEnabled": true,
  "kickstartDuration": 500
}
```

#### Notes

- ⚠️ **Requires 3 pins**: Relay 1 (`pin`), Relay 2 (`pinRelay2`), Relay 3 (`pinRelay3`)
- `inverted: true` means relays are Active Low (common for relay modules)
- **Safety**: All relays are turned OFF before applying new state to prevent transient states
- **Watchdog**: Resets to `defaultState` if no message received within `pollingInterval`
- **State Publishing**: Current speed value (0-4) is published to `/state` topic every `pollingInterval` ms
- **Kickstart**: When enabled, state feedback shows the target speed immediately (not the temporary full power state)
