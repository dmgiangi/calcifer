//
// IDisplay.h - Abstract interface for display devices
// Follows Dependency Inversion Principle: depend on abstractions, not concretions
//

#pragma once

#include <Arduino.h>

/**
 * @brief Abstract interface for display devices.
 * 
 * Provides a hardware-agnostic API for character-based displays.
 * Implementations can support various display types:
 * - I2C LCD (PCF8574T-based)
 * - OLED displays
 * - TFT displays
 * - E-Ink displays
 * 
 * @note All implementations must be non-blocking.
 */
class IDisplay {
public:
    virtual ~IDisplay() = default;

    /**
     * @brief Initializes the display hardware.
     * @return true if initialization succeeded, false otherwise.
     */
    virtual bool init() = 0;

    /**
     * @brief Clears all content from the display.
     */
    virtual void clear() = 0;

    /**
     * @brief Sets the cursor position for subsequent print operations.
     * @param col Column position (0-based, left to right)
     * @param row Row position (0-based, top to bottom)
     */
    virtual void setCursor(uint8_t col, uint8_t row) = 0;

    /**
     * @brief Prints text at the current cursor position.
     * @param text The text to display
     */
    virtual void print(const String& text) = 0;

    /**
     * @brief Prints a single character at the current cursor position.
     * @param c The character to display
     */
    virtual void printChar(char c) = 0;

    /**
     * @brief Gets the number of columns (characters per row).
     * @return Number of columns
     */
    virtual uint8_t getCols() const = 0;

    /**
     * @brief Gets the number of rows.
     * @return Number of rows
     */
    virtual uint8_t getRows() const = 0;

    /**
     * @brief Controls the display backlight.
     * @param on true to turn on, false to turn off
     */
    virtual void setBacklight(bool on) = 0;

    /**
     * @brief Checks if the display is properly initialized.
     * @return true if display is ready for use
     */
    virtual bool isReady() const = 0;
};

