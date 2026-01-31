//
// I2CLcdDisplay.h - PCF8574T-based I2C LCD display implementation
//

#pragma once

#include "../IDisplay.h"
#include <LiquidCrystal_I2C.h>
#include <memory>

/**
 * @brief Configuration for I2C LCD display.
 */
struct I2CLcdConfig {
    uint8_t i2cAddress;     // I2C address (typically 0x27 or 0x3F)
    uint8_t cols;           // Number of columns (16 or 20)
    uint8_t rows;           // Number of rows (2 or 4)
    int sdaPin;             // I2C SDA pin (default: 21)
    int sclPin;             // I2C SCL pin (default: 22)
    
    I2CLcdConfig() 
        : i2cAddress(0x27), cols(20), rows(4), sdaPin(21), sclPin(22) {}
};

/**
 * @brief IDisplay implementation for PCF8574T-based I2C LCD displays.
 * 
 * Supports common character LCD modules:
 * - 16x2 LCD with I2C backpack
 * - 20x4 LCD with I2C backpack
 * 
 * Uses the LiquidCrystal_I2C library for hardware communication.
 */
class I2CLcdDisplay : public IDisplay {
public:
    /**
     * @brief Constructs an I2C LCD display with the given configuration.
     * @param config Display configuration
     */
    explicit I2CLcdDisplay(const I2CLcdConfig& config);
    
    ~I2CLcdDisplay() override = default;

    // IDisplay interface implementation
    bool init() override;
    void clear() override;
    void setCursor(uint8_t col, uint8_t row) override;
    void print(const String& text) override;
    void printChar(char c) override;
    uint8_t getCols() const override { return config_.cols; }
    uint8_t getRows() const override { return config_.rows; }
    void setBacklight(bool on) override;
    bool isReady() const override { return initialized_; }

private:
    I2CLcdConfig config_;
    std::unique_ptr<LiquidCrystal_I2C> lcd_;
    bool initialized_;
};

