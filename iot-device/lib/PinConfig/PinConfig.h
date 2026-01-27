//
// Created by fubus on 12/10/25.
//

#pragma once
#include <Arduino.h>
#include <vector>

/**
 * @brief Structure defining the capabilities of a specific pin based on ESP32 DevKit C V4 specs.
 */
struct AllowedConfig
{
    int gpio;           // GPIO Number
    bool isInput;       // Supports Digital Input
    bool isOutput;      // Supports Digital Output
    bool isPWM;         // Supports PWM (LED Control/Servo)
    bool isAnalogIn;    // Supports ADC (Analog to Digital)
    bool isDAC;         // Supports DAC (Digital to Analog)
    bool isSPI;         // Native Hardware SPI (VSPI/HSPI)
    bool isOneWire;     // Supports OneWire (Requires Open-Drain Output)
    bool isInterrupt;   // Supports Hardware Interrupts
};

/**
 * @brief Supported pin modes.
 */
enum PinModeType
{
    INPUT_DIGITAL,
    OUTPUT_DIGITAL,
    PWM,
    INPUT_ANALOG,
    OUTPUT_ANALOG,
    DHT22_SENSOR,
    YL_69_SENSOR,
    DS18B20,
    THERMOCOUPLE, // Represents a complete SPI thermocouple interface (CS, SCK, SO)
    FAN,          // 3-relay fan control with 5 discrete speed states
    INVALID
};

/**
 * @brief Configuration object for a single pin or device.
 */
struct PinConfig
{
    int pin;                // Primary GPIO (e.g., Control, ADC, CS for SPI, or Relay 1 for FAN)
    int pinClock;           // Optional: SPI Clock (SCK)
    int pinData;            // Optional: SPI MISO (SO)
    int pinRelay2;          // Optional: Second relay GPIO (FAN mode)
    int pinRelay3;          // Optional: Third relay GPIO (FAN mode)
    PinModeType mode;       // Operation mode
    String name;            // Human-readable name
    int defaultState;       // Initial state (0-100 for FAN mode)
    int pollingInterval;    // Interval in ms for sensors
    bool inverted;          // Logic inversion (Active Low)
    bool kickstartEnabled;  // Optional: Enable kickstart for FAN mode (default: false)
    int kickstartDuration;  // Optional: Kickstart duration in ms for FAN mode (default: 0)
};

/**
 * @brief Parses a string representation of a pin mode into the corresponding enum.
 */
PinModeType parseMode(const String& s);

/**
 * @brief Validates if a specific pin supports the requested capability.
 */
bool isPinCapabilityValid(int pin, bool requiresOutput, bool requiresInput, bool requiresAnalog = false, bool requiresOneWire = false);

/**
 * @brief Validates the entire configuration object based on its mode.
 */
bool isValidConfig(const PinConfig& config);

/**
 * @brief Loads pin configurations from a JSON file.
 */
std::vector<PinConfig> loadConfiguration(const char* filename);
