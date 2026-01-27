# PinConfig Library

Handles JSON-based hardware configuration and ESP32 pin capability validation. This library parses `pin_config.json` from SPIFFS and validates that each pin supports the requested functionality based on ESP32 DevKit C V4 specifications.

## Overview

The PinConfig library provides:
- **JSON Parsing**: Load device configurations from SPIFFS filesystem
- **Pin Validation**: Verify GPIO capabilities before initialization
- **Mode Parsing**: Convert string mode names to typed enums
- **Hardware Safety**: Prevent invalid configurations at runtime

## Data Structures

### PinModeType (Enum)

Defines all supported device modes:

| Mode             | Description                        | Requirements                                      |
|:-----------------|:-----------------------------------|:--------------------------------------------------|
| `INPUT_DIGITAL`  | Digital input (buttons, switches)  | GPIO with input capability                        |
| `OUTPUT_DIGITAL` | Digital output (relays, LEDs)      | GPIO with output capability                       |
| `PWM`            | PWM output (dimmers, motors)       | GPIO with PWM capability                          |
| `INPUT_ANALOG`   | ADC input (sensors)                | ADC-capable GPIO                                  |
| `OUTPUT_ANALOG`  | DAC output (analog voltage)        | DAC-capable GPIO (25, 26 only)                    |
| `DHT22_SENSOR`   | DHT22 temperature/humidity         | GPIO with input AND output                        |
| `YL_69_SENSOR`   | Soil moisture sensor               | ADC-capable GPIO                                  |
| `DS18B20`        | OneWire temperature sensor         | GPIO with OneWire support                         |
| `THERMOCOUPLE`   | MAX6675 SPI thermocouple           | 3 GPIOs: CS (out), SCK (out), SO (in)             |
| `FAN`            | 3-relay discrete fan speed control | 3 GPIOs: relay1 (out), relay2 (out), relay3 (out) |
| `INVALID`        | Parse error or unknown mode        | -                                                 |

### PinConfig (Struct)

Configuration for a single device:

```cpp
struct PinConfig {
    int pin;              // Primary GPIO number (Relay 1 for FAN)
    int pinClock;         // SPI Clock pin (THERMOCOUPLE only)
    int pinData;          // SPI Data pin (THERMOCOUPLE only)
    int pinRelay2;        // Second relay GPIO (FAN only)
    int pinRelay3;        // Third relay GPIO (FAN only)
    PinModeType mode;     // Operation mode
    String name;          // Human-readable identifier (used in MQTT topics)
    int defaultState;     // Initial state (0/1 for digital, 0-100 for FAN)
    int pollingInterval;  // Interval in ms (sensors: publish rate, actuators: watchdog)
    bool inverted;        // Logic inversion (Active Low)
};
```

### AllowedConfig (Struct)

Internal structure defining GPIO capabilities:

```cpp
struct AllowedConfig {
    int gpio;           // GPIO Number
    bool isInput;       // Supports Digital Input
    bool isOutput;      // Supports Digital Output
    bool isPWM;         // Supports PWM
    bool isAnalogIn;    // Supports ADC
    bool isDAC;         // Supports DAC
    bool isSPI;         // Native SPI support
    bool isOneWire;     // Supports OneWire protocol
    bool isInterrupt;   // Supports hardware interrupts
};
```

## ESP32 Pin Capability Table

Based on ESP32 DevKit C V4 specifications:

| GPIO | Input | Output | PWM | ADC | DAC | SPI | OneWire | Notes |
|:----:|:-----:|:------:|:---:|:---:|:---:|:---:|:-------:|:------|
| 13 | ✓ | ✓ | ✓ | - | - | ✓ | ✓ | HSPI_MOSI |
| 14 | ✓ | ✓ | ✓ | - | - | ✓ | ✓ | HSPI_CLK |
| 16 | ✓ | ✓ | ✓ | - | - | - | ✓ | UART2_RX |
| 17 | ✓ | ✓ | ✓ | - | - | - | ✓ | UART2_TX |
| 18 | ✓ | ✓ | ✓ | - | - | ✓ | ✓ | VSPI_CLK |
| 19 | ✓ | ✓ | ✓ | - | - | ✓ | ✓ | VSPI_MISO |
| 21 | ✓ | ✓ | ✓ | - | - | - | ✓ | I2C_SDA |
| 22 | ✓ | ✓ | ✓ | - | - | - | ✓ | I2C_SCL |
| 23 | ✓ | ✓ | ✓ | - | - | ✓ | ✓ | VSPI_MOSI |
| 25 | ✓ | ✓ | ✓ | - | ✓ | - | ✓ | **DAC1** |
| 26 | ✓ | ✓ | ✓ | - | ✓ | - | ✓ | **DAC2** |
| 27 | ✓ | ✓ | ✓ | - | - | - | ✓ | ADC2-7 |
| 32 | ✓ | ✓ | ✓ | ✓ | - | - | ✓ | ADC1-4 |
| 33 | ✓ | ✓ | ✓ | ✓ | - | - | ✓ | ADC1-5 |
| 34 | ✓ | - | - | ✓ | - | - | - | **Input Only** |
| 35 | ✓ | - | - | ✓ | - | - | - | **Input Only** |
| 36 | ✓ | - | - | ✓ | - | - | - | **Input Only** |
| 39 | ✓ | - | - | ✓ | - | - | - | **Input Only** |

> ⚠️ **ADC2 pins** (0, 2, 4, 12-15, 25-27) cannot be used when WiFi is active.

## JSON Configuration Schema

The `pin_config.json` file is a JSON array of device configurations:

```json
[
  {
    "pin": 2,
    "mode": "OUTPUT_DIGITAL",
    "name": "relay-1",
    "defaultState": 0,
    "inverted": true,
    "pollingInterval": 5000
  },
  {
    "pin": 32,
    "mode": "INPUT_ANALOG",
    "name": "light-sensor",
    "pollingInterval": 1000
  },
  {
    "pin": 5,
    "mode": "THERMOCOUPLE",
    "name": "kiln-temp",
    "sck": 18,
    "so": 19,
    "pollingInterval": 2000
  },
  {
    "pin": 26,
    "pinRelay2": 25,
    "pinRelay3": 14,
    "mode": "FAN",
    "name": "ceiling-fan",
    "defaultState": 0,
    "pollingInterval": 30000,
    "inverted": true
  }
]
```

### Field Reference

| Field             | Type   |   Required   | Default | Description                             |
|:------------------|:-------|:------------:|:-------:|:----------------------------------------|
| `pin`             | int    |      ✓       |    -    | Primary GPIO number (relay pin for FAN) |
| `mode`            | string |      ✓       |    -    | One of the `PinModeType` values         |
| `name`            | string |      ✓       |    -    | Identifier used in MQTT topics          |
| `defaultState`    | int    |      -       |   `0`   | Initial state (actuators only)          |
| `pollingInterval` | int    |      -       | `1000`  | Interval in milliseconds                |
| `inverted`        | bool   |      -       | `false` | Invert logic (Active Low relay)         |
| `sck`             | int    | THERMOCOUPLE |    -    | SPI Clock GPIO                          |
| `so` / `miso`     | int    | THERMOCOUPLE |    -    | SPI Data In GPIO                        |
| `pinRelay2`       | int    |     FAN      |    -    | Second relay GPIO (output capable)      |
| `pinRelay3`       | int    |     FAN      |    -    | Third relay GPIO (output capable)       |

## API Reference

### `loadConfiguration(filename)`

Loads and validates pin configurations from a JSON file.

```cpp
std::vector<PinConfig> loadConfiguration(const char* filename);
```

**Parameters:**
- `filename`: Path to JSON file (e.g., `"/pin_config.json"`)

**Returns:** Vector of valid `PinConfig` objects. Invalid entries are logged and skipped.

### `parseMode(modeString)`

Converts a string mode name to its enum value.

```cpp
PinModeType parseMode(const String& s);
```

**Returns:** `PinModeType` enum value, or `INVALID` if unknown.

### `isValidConfig(config)`

Validates a configuration against ESP32 pin capabilities.

```cpp
bool isValidConfig(const PinConfig& config);
```

**Returns:** `true` if the configuration is valid for the hardware.

### `isPinCapabilityValid(pin, requiresOutput, requiresInput, requiresAnalog, requiresOneWire)`

Low-level check for specific pin capabilities.

```cpp
bool isPinCapabilityValid(int pin, bool requiresOutput, bool requiresInput, 
                          bool requiresAnalog = false, bool requiresOneWire = false);
```

## Usage Example

```cpp
#include <SPIFFS.h>
#include <PinConfig.h>

void setup() {
    Serial.begin(115200);
    SPIFFS.begin(true);
    
    // Load configurations
    std::vector<PinConfig> configs = loadConfiguration("/pin_config.json");
    
    // Process valid configurations
    for (const auto& cfg : configs) {
        Serial.printf("Loaded: %s (GPIO%d) as %d\n", 
                      cfg.name.c_str(), cfg.pin, cfg.mode);
    }
}
```

