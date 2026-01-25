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
    FAN,          // AC dimmer fan control (relay + TRIAC dimmer + zero-cross detection)
    INVALID
};

/**
 * @brief Configuration object for a single pin or device.
 */
struct PinConfig
{
    int pin;                // Primary GPIO (e.g., Control, ADC, or CS for SPI)
    int pinClock;           // Optional: SPI Clock (SCK)
    int pinData;            // Optional: SPI MISO (SO)
    int pinDimmer;          // Optional: AC Dimmer TRIAC control GPIO (FAN mode)
    int pinZeroCross;       // Optional: Zero-crossing detection GPIO (FAN mode)
    int minPwm;             // Optional: Minimum PWM threshold for hardware (FAN mode, 0-100)
    String curveType;       // Optional: Dimming curve type (FAN mode: "LINEAR", "RMS", "LOGARITHMIC")
    PinModeType mode;       // Operation mode
    String name;            // Human-readable name
    int defaultState;       // Initial state
    int pollingInterval;    // Interval in ms for sensors
    bool inverted;          // Logic inversion (Active Low)
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
