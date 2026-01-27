//
// Created by fubus on 12/10/25.
// Clean code refactoring with ArduinoJson
//

#include "PinConfig.h"
#include <SPIFFS.h>
#include <ArduinoJson.h>
#include <Logger.h>

static const char* TAG = "PinConfig";

// ----------------------------
// Constants
// ----------------------------
namespace
{
    constexpr int PWM_FREQUENCY = 5000;
    constexpr int PWM_RESOLUTION = 8;
    int nextPwmChannel = 0; // used to assign PWM channels
}

// ----------------------------
// Allowed pin table
// Each pin defines what functionality is supported based on ESP32 DevKit C V4
// ----------------------------
const AllowedConfig allowedPins[] = {
    // GPIO |  IN  | OUT  | PWM  | ADC  | DAC  | SPI  | 1-W  | INT
    {13,    true,  true,  true,  false, false, true,  true,  true}, // GPIO13 (HSPI_MOSI)
    {14,    true,  true,  true,  false, false, true,  true,  true}, // GPIO14 (HSPI_CLK)
    {16,    true,  true,  true,  false, false, false, true,  true}, // GPIO16 (UART2_RX)
    {17,    true,  true,  true,  false, false, false, true,  true}, // GPIO17 (UART2_TX)
    {18,    true,  true,  true,  false, false, true,  true,  true}, // GPIO18 (VSPI_CLK)
    {19,    true,  true,  true,  false, false, true,  true,  true}, // GPIO19 (VSPI_MISO)
    {21,    true,  true,  true,  false, false, false, true,  true}, // GPIO21 (I2C_SDA)
    {22,    true,  true,  true,  false, false, false, true,  true}, // GPIO22 (I2C_SCL)
    {23,    true,  true,  true,  false, false, true,  true,  true}, // GPIO23 (VSPI_MOSI)
    {27,    true,  true,  true,  false, false, false, true,  true}, // GPIO27 (ADC2-7)
    {25,    true,  true,  true,  false, true,  false, true,  true}, // GPIO25 (DAC1)
    {26,    true,  true,  true,  false, true,  false, true,  true}, // GPIO26 (DAC2)
    {32,    true,  true,  true,  true,  false, false, true,  true}, // GPIO32 (ADC1-4)
    {33,    true,  true,  true,  true,  false, false, true,  true}, // GPIO33 (ADC1-5)
    // Input Only Pins
    {34,    true,  false, false, true,  false, false, false, true}, // GPIO34
    {35,    true,  false, false, true,  false, false, false, true}, // GPIO35
    {36,    true,  false, false, true,  false, false, false, true}, // GPIO36
    {39,    true,  false, false, true,  false, false, false, true}, // GPIO39
};

const size_t allowedCount = sizeof(allowedPins) / sizeof(allowedPins[0]);

// ----------------------------
// Parse pin mode string into enum
// ----------------------------
PinModeType parseMode(const String &s)
{
    String m = s;
    m.toUpperCase();

    if (m == "INPUT_DIGITAL") return INPUT_DIGITAL;
    if (m == "OUTPUT_DIGITAL") return OUTPUT_DIGITAL;
    if (m == "PWM") return PWM;
    if (m == "INPUT_ANALOG") return INPUT_ANALOG;
    if (m == "OUTPUT_ANALOG") return OUTPUT_ANALOG;
    if (m == "DHT22_SENSOR") return DHT22_SENSOR;
    if (m == "YL_69_SENSOR") return YL_69_SENSOR;
    if (m == "DS18B20") return DS18B20;
    if (m == "THERMOCOUPLE") return THERMOCOUPLE;
    if (m == "FAN") return FAN;
    return INVALID;
}

// ----------------------------
// Helper: Check physical capability of a single pin
// ----------------------------
bool isPinCapabilityValid(int pin, bool requiresOutput, bool requiresInput, bool requiresAnalog, bool requiresOneWire) {
    for (size_t i = 0; i < allowedCount; i++)
    {
        if (allowedPins[i].gpio == pin)
        {
            if (requiresOutput && !allowedPins[i].isOutput) return false;
            if (requiresInput && !allowedPins[i].isInput) return false;
            if (requiresAnalog && !allowedPins[i].isAnalogIn) return false;
            if (requiresOneWire && !allowedPins[i].isOneWire) return false;
            return true;
        }
    }
    return false;
}

// ----------------------------
// Validate if the configuration is valid
// ----------------------------
bool isValidConfig(const PinConfig& config)
{
    int pin = config.pin;
    
    // First, validate the primary pin exists
    bool primaryPinFound = false;
    const AllowedConfig* primaryPinCfg = nullptr;
    
    for (size_t i = 0; i < allowedCount; i++) {
        if (allowedPins[i].gpio == pin) {
            primaryPinFound = true;
            primaryPinCfg = &allowedPins[i];
            break;
        }
    }
    if (!primaryPinFound) return false;

    switch (config.mode)
    {
    case INPUT_DIGITAL:
        return primaryPinCfg->isInput;
    case OUTPUT_DIGITAL:
        return primaryPinCfg->isOutput;
    case PWM:
        return primaryPinCfg->isPWM;
    case OUTPUT_ANALOG:
        return primaryPinCfg->isDAC;
    case INPUT_ANALOG:
    case YL_69_SENSOR:
        return primaryPinCfg->isAnalogIn;
    
    // DHT22 requires bidirectional digital IO (usually handled by the library switching modes)
    case DHT22_SENSOR:
        return primaryPinCfg->isInput && primaryPinCfg->isOutput;

    // OneWire dedicated check
    case DS18B20:
        return primaryPinCfg->isOneWire;

    // Composite SPI Device (Thermocouple)
    case THERMOCOUPLE:
        // 1. Validate CS (Primary Pin) -> Must be Output
        if (!primaryPinCfg->isOutput) return false;

        // 2. Validate SCK (pinClock) -> Must be Output
        if (!isPinCapabilityValid(config.pinClock, true, false)) return false;

        // 3. Validate SO/MISO (pinData) -> Must be Input
        if (!isPinCapabilityValid(config.pinData, false, true)) return false;

        return true;

    // 3-Relay Fan Control (5 discrete speed states)
    case FAN:
    {
        // 1. Validate Relay 1 pin (Primary Pin) -> Must be Output
        if (!primaryPinCfg->isOutput) return false;

        // 2. Validate Relay 2 pin (pinRelay2) -> Must be Output
        if (!isPinCapabilityValid(config.pinRelay2, true, false)) return false;

        // 3. Validate Relay 3 pin (pinRelay3) -> Must be Output
        if (!isPinCapabilityValid(config.pinRelay3, true, false)) return false;

        return true;
    }

    default:
        return false;
    }
}

// ----------------------------
// Load pin configuration from JSON file
// ----------------------------
std::vector<PinConfig> loadConfiguration(const char *filename)
{
    std::vector<PinConfig> configs;

    // --- Open file ---
    if (!SPIFFS.exists(filename)) {
         LOG_ERROR(TAG, "Config file %s not found!", filename);
         return {};
    }

    File file = SPIFFS.open(filename, "r");
    if (!file)
    {
        LOG_ERROR(TAG, "Failed to open file %s", filename);
        return {};
    }

    if (file.size() == 0)
    {
        LOG_ERROR(TAG, "Config file %s is empty!", filename);
        file.close();
        return {};
    }

    // --- Read File Content ---
    String fileContent = file.readString();
    file.close();

    // --- Parse JSON ---
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, fileContent);

    if (err)
    {
        LOG_ERROR(TAG, "JSON parse error in %s: %s", filename, err.c_str());
        return {};
    }

    // Expecting the document to be a JSON array at the root
    if (!doc.is<JsonArray>())
    {
        LOG_ERROR(TAG, "Expected JSON array at root in %s", filename);
        return {};
    }

    // --- Iterate pins array ---
    JsonArray pins = doc.as<JsonArray>();
    for (auto obj : pins)
    {
        int pin = obj["pin"] | -1;
        String modeStr = obj["mode"] | "";
        String nameStr = obj["name"] | "";
        int defaultState = obj["defaultState"] | 0;
        int pollingInterval = obj["pollingInterval"] | 1000;
        bool inverted = obj["inverted"] | false;
        
        // SPI pins (Thermocouple)
        int pinClock = obj["sck"] | -1;
        int pinData = obj["so"] | -1;
        if (pinData == -1) pinData = obj["miso"] | -1;

        // FAN-specific fields (3-relay control)
        int pinRelay2 = obj["pinRelay2"] | -1;
        int pinRelay3 = obj["pinRelay3"] | -1;

        if (pin == -1 || modeStr.isEmpty())
        {
            LOG_WARN(TAG, "Invalid pin entry, skipping...");
            continue;
        }

        PinModeType mode = parseMode(modeStr);
        if (mode == INVALID)
        {
            LOG_WARN(TAG, "Invalid mode: %s", modeStr.c_str());
            continue;
        }

        PinConfig cfg;
        cfg.pin = pin;
        cfg.pinClock = pinClock;
        cfg.pinData = pinData;
        cfg.pinRelay2 = pinRelay2;
        cfg.pinRelay3 = pinRelay3;
        cfg.mode = mode;
        cfg.name = nameStr;
        cfg.defaultState = defaultState;
        cfg.pollingInterval = pollingInterval;
        cfg.inverted = inverted;

        if (!isValidConfig(cfg))
        {
            LOG_WARN(TAG, "Invalid hardware config for %s (GPIO%d)",
                     nameStr.c_str(), pin);
            continue;
        }

        configs.push_back(cfg);

        if (mode == THERMOCOUPLE) {
            LOG_INFO(TAG, "Loaded: %s (CS:%d, SCK:%d, SO:%d)",
                     modeStr.c_str(), cfg.pin, cfg.pinClock, cfg.pinData);
        } else if (mode == FAN) {
            LOG_INFO(TAG, "Loaded: %s (R1:%d, R2:%d, R3:%d)",
                     cfg.name.c_str(), cfg.pin, cfg.pinRelay2, cfg.pinRelay3);
        } else {
            LOG_INFO(TAG, "Loaded: GPIO%d as %s (%s)",
                     cfg.pin, modeStr.c_str(), cfg.name.c_str());
        }
    }

    return configs;
}