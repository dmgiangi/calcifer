# DisplayManager Library

A modular, extensible display management system for ESP32 IoT applications.

## Architecture Overview

The DisplayManager follows the **Strategy Pattern** and **Dependency Inversion Principle** to provide a flexible display
system that can work with various display hardware.

### Core Components

```
DisplayManager (Singleton)
├── IDisplay (Interface)           - Abstract display hardware
│   └── I2CLcdDisplay              - PCF8574T I2C LCD implementation
└── IDisplayDataProvider (Interface) - Abstract data source
    └── MqttDataProvider           - Bridges to MqttManager data
```

### Key Interfaces

**IDisplay** - Hardware abstraction for displays:

- `init()`, `clear()`, `setCursor()`, `print()`, `setBacklight()`
- Enables support for LCD, OLED, TFT, E-Ink displays

**IDisplayDataProvider** - Data source abstraction:

- `getDisplayableItems()` - Returns sensor/actuator values
- `getConnectionStatus()` - Returns WiFi/MQTT connection state
- `refresh()` - Updates cached data from sources

## Configuration

Create `data/display_config.json`:

```json
{
  "enabled": true,
  "type": "I2C_LCD",
  "i2c_address": "0x27",
  "cols": 20,
  "rows": 4,
  "rotationInterval": 3000,
  "sda": 21,
  "scl": 22
}
```

| Field            | Type       | Default   | Description                       |
|------------------|------------|-----------|-----------------------------------|
| enabled          | bool       | false     | Enable/disable display            |
| type             | string     | "I2C_LCD" | Display type                      |
| i2c_address      | string/int | 0x27      | I2C address (0x27 or 0x3F common) |
| cols             | int        | 20        | Display columns (16 or 20)        |
| rows             | int        | 4         | Display rows (2 or 4)             |
| rotationInterval | int        | 3000      | Time between item rotation (ms)   |
| sda              | int        | 21        | I2C SDA pin                       |
| scl              | int        | 22        | I2C SCL pin                       |

## Usage

```cpp
#include <DisplayManager.h>
#include "providers/MqttDataProvider.h"

void setup() {
    // ... WiFi and MQTT initialization ...
    
    if (DisplayManager::loadConfig("/display_config.json")) {
        auto provider = std::make_unique<MqttDataProvider>(pinConfigs);
        DisplayManager::init(std::move(provider));
    }
}

void loop() {
    // ... other loop code ...
    DisplayManager::update();
}
```

## Display Behavior

### Normal Mode

- Rotates through all sensors and actuators
- Shows device name, type, current value
- For actuators: shows current state
- Shows item counter (e.g., "1/5")

### Error Mode

- Activates when WiFi or MQTT disconnects
- Overrides normal rotation
- Shows error message until connection restored

## Adding New Display Types

1. Create new class implementing `IDisplay`:

```cpp
// lib/DisplayManager/displays/OledDisplay.h
class OledDisplay : public IDisplay {
    bool init() override { /* Initialize OLED */ }
    void clear() override { /* Clear screen */ }
    // ... implement all methods
};
```

2. Add factory logic in `DisplayManager::init()`:

```cpp
if (config.type == "OLED") {
    display_ = std::make_unique<OledDisplay>(config);
}
```

3. No changes needed to existing code (Open/Closed Principle)

## Adding New Data Providers

1. Implement `IDisplayDataProvider`:

```cpp
class CustomDataProvider : public IDisplayDataProvider {
    std::vector<DisplayItem> getDisplayableItems() override;
    ConnectionStatus getConnectionStatus() override;
    void refresh() override;
};
```

2. Pass to `DisplayManager::init()`:

```cpp
auto provider = std::make_unique<CustomDataProvider>();
DisplayManager::init(std::move(provider));
```

## Supported Display Types

| Type    | Hardware         | Library           |
|---------|------------------|-------------------|
| I2C_LCD | PCF8574T I2C LCD | LiquidCrystal_I2C |

## Dependencies

- `marcoschwartz/LiquidCrystal_I2C@^1.1.4`
- ArduinoJson (for configuration)
- SPIFFS (for configuration file storage)

