//
// I2CLcdDisplay.cpp - PCF8574T-based I2C LCD display implementation
//

#include "I2CLcdDisplay.h"
#include <Wire.h>
#include <Logger.h>

static const char* TAG = "I2CLcd";

I2CLcdDisplay::I2CLcdDisplay(const I2CLcdConfig& config)
    : config_(config)
    , lcd_(nullptr)
    , initialized_(false)
{
}

bool I2CLcdDisplay::init() {
    LOG_INFO(TAG, "Initializing I2C LCD at address 0x%02X (%dx%d)", 
             config_.i2cAddress, config_.cols, config_.rows);
    
    // Initialize I2C with custom pins if specified
    Wire.begin(config_.sdaPin, config_.sclPin);
    
    // Check if device is present on I2C bus
    Wire.beginTransmission(config_.i2cAddress);
    uint8_t error = Wire.endTransmission();
    
    if (error != 0) {
        LOG_ERROR(TAG, "I2C LCD not found at address 0x%02X (error: %d)", 
                  config_.i2cAddress, error);
        return false;
    }
    
    // Create LCD instance
    lcd_ = std::make_unique<LiquidCrystal_I2C>(
        config_.i2cAddress, 
        config_.cols, 
        config_.rows
    );
    
    // Initialize the LCD
    lcd_->init();
    lcd_->backlight();
    lcd_->clear();
    
    initialized_ = true;
    LOG_INFO(TAG, "I2C LCD initialized successfully");
    
    return true;
}

void I2CLcdDisplay::clear() {
    if (lcd_ && initialized_) {
        lcd_->clear();
    }
}

void I2CLcdDisplay::setCursor(uint8_t col, uint8_t row) {
    if (lcd_ && initialized_) {
        // Clamp values to valid range
        col = min(col, (uint8_t)(config_.cols - 1));
        row = min(row, (uint8_t)(config_.rows - 1));
        lcd_->setCursor(col, row);
    }
}

void I2CLcdDisplay::print(const String& text) {
    if (lcd_ && initialized_) {
        lcd_->print(text);
    }
}

void I2CLcdDisplay::printChar(char c) {
    if (lcd_ && initialized_) {
        lcd_->write(c);
    }
}

void I2CLcdDisplay::setBacklight(bool on) {
    if (lcd_ && initialized_) {
        if (on) {
            lcd_->backlight();
        } else {
            lcd_->noBacklight();
        }
    }
}

