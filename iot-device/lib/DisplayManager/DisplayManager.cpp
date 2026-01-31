//
// DisplayManager.cpp - Singleton manager for display operations
//

#include "DisplayManager.h"
#include "displays/I2CLcdDisplay.h"
#include <ArduinoJson.h>
#include <SPIFFS.h>
#include <Logger.h>

static const char* TAG = "Display";

// ============================================================================
// Singleton Implementation
// ============================================================================

DisplayManager& DisplayManager::getInstance() {
    static DisplayManager instance;
    return instance;
}

// ============================================================================
// Configuration Loading
// ============================================================================

DisplayLayout DisplayManager::parseLayout(const String& layoutStr) {
    if (layoutStr == "16x2") {
        return DisplayLayout::LAYOUT_16x2;
    }
    // Default to 20x4
    return DisplayLayout::LAYOUT_20x4;
}

bool DisplayManager::loadConfig(const char* filename) {
    DisplayManager& instance = getInstance();

    LOG_INFO(TAG, "Loading display config from %s", filename);

    File file = SPIFFS.open(filename, "r");
    if (!file) {
        LOG_WARN(TAG, "Display config file not found, display disabled");
        instance.config_.enabled = false;
        return true;  // Not an error - display is optional
    }

    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, file);
    file.close();

    if (error) {
        LOG_ERROR(TAG, "Failed to parse display config: %s", error.c_str());
        return false;
    }

    // Parse configuration
    instance.config_.enabled = doc["enabled"] | false;
    instance.config_.type = doc["type"] | "I2C_LCD";
    instance.config_.rotationInterval = doc["rotationInterval"] | 3000;
    instance.config_.scrollSpeed = doc["scrollSpeed"] | 400;
    instance.config_.sdaPin = doc["sda"] | 21;
    instance.config_.sclPin = doc["scl"] | 22;

    // Parse layout (e.g., "16x2", "20x4")
    String layoutStr = doc["layout"] | "20x4";
    instance.config_.layout = parseLayout(layoutStr);

    // Parse I2C address (can be string "0x27" or number)
    if (doc["i2c_address"].is<const char*>()) {
        String addrStr = doc["i2c_address"] | "0x27";
        instance.config_.i2cAddress = (uint8_t)strtol(addrStr.c_str(), nullptr, 16);
    } else {
        instance.config_.i2cAddress = doc["i2c_address"] | 0x27;
    }

    LOG_INFO(TAG, "Display config: enabled=%d, type=%s, addr=0x%02X, layout=%s, scroll=%lums",
             instance.config_.enabled, instance.config_.type.c_str(),
             instance.config_.i2cAddress, layoutStr.c_str(), instance.config_.scrollSpeed);

    return true;
}

// ============================================================================
// Initialization
// ============================================================================

bool DisplayManager::init(std::unique_ptr<IDisplayDataProvider> dataProvider) {
    DisplayManager& instance = getInstance();
    
    if (!instance.config_.enabled) {
        LOG_INFO(TAG, "Display is disabled in configuration");
        return true;
    }
    
    instance.dataProvider_ = std::move(dataProvider);
    
    // Create display based on type
    if (instance.config_.type == "I2C_LCD") {
        I2CLcdConfig lcdConfig;
        lcdConfig.i2cAddress = instance.config_.i2cAddress;
        lcdConfig.cols = instance.config_.getCols();
        lcdConfig.rows = instance.config_.getRows();
        lcdConfig.sdaPin = instance.config_.sdaPin;
        lcdConfig.sclPin = instance.config_.sclPin;

        instance.display_ = std::make_unique<I2CLcdDisplay>(lcdConfig);
    } else {
        LOG_ERROR(TAG, "Unknown display type: %s", instance.config_.type.c_str());
        return false;
    }
    
    // Initialize display hardware
    if (!instance.display_->init()) {
        LOG_ERROR(TAG, "Failed to initialize display hardware");
        return false;
    }
    
    // Initialize state
    instance.currentItemIndex_ = 0;
    instance.lastRotation_ = millis();
    instance.lastDataRefresh_ = 0;
    instance.inErrorMode_ = false;
    instance.initialized_ = true;

    // Initialize scroll state
    instance.resetScroll();
    
    // Initial data refresh
    if (instance.dataProvider_) {
        instance.dataProvider_->refresh();
    }
    
    // Show startup message
    instance.display_->clear();
    instance.display_->setCursor(0, 0);
    instance.display_->print("IoT Display Ready");
    
    LOG_INFO(TAG, "Display initialized successfully");
    return true;
}

// ============================================================================
// Main Update Loop
// ============================================================================

bool DisplayManager::isEnabled() {
    return getInstance().config_.enabled && getInstance().initialized_;
}

void DisplayManager::update() {
    DisplayManager& instance = getInstance();

    if (!instance.config_.enabled || !instance.initialized_) {
        return;
    }

    unsigned long now = millis();

    // Refresh data periodically
    if (now - instance.lastDataRefresh_ >= DATA_REFRESH_INTERVAL) {
        instance.lastDataRefresh_ = now;
        if (instance.dataProvider_) {
            instance.dataProvider_->refresh();
        }
    }

    // Check connection status
    if (instance.dataProvider_) {
        ConnectionStatus status = instance.dataProvider_->getConnectionStatus();

        if (status.hasError()) {
            if (!instance.inErrorMode_) {
                instance.inErrorMode_ = true;
                instance.renderErrorMessage(status.errorMessage);
            }
            return;  // Don't rotate while in error mode
        } else if (instance.inErrorMode_) {
            // Recovered from error
            instance.inErrorMode_ = false;
            instance.display_->clear();
            instance.resetScroll();
        }
    }

    // Rotate items at configured interval
    if (now - instance.lastRotation_ >= instance.config_.rotationInterval) {
        instance.lastRotation_ = now;
        instance.rotateToNextItem();
        instance.resetScroll();  // Reset scroll when changing device
        instance.renderCurrentItem();
    }

    // Update scroll animation for long names
    if (now - instance.lastScrollUpdate_ >= instance.config_.scrollSpeed) {
        instance.lastScrollUpdate_ = now;

        // Get current item to check if scroll is needed
        auto items = instance.dataProvider_ ? instance.dataProvider_->getDisplayableItems()
                                             : std::vector<DisplayItem>();
        if (!items.empty() && instance.currentItemIndex_ < items.size()) {
            const DisplayItem& item = items[instance.currentItemIndex_];
            uint8_t maxLen = instance.config_.getMaxNameLength();

            if (item.deviceName.length() > maxLen) {
                instance.updateScroll(item.deviceName, maxLen);
                instance.renderCurrentItem();
            }
        }
    }
}

// ============================================================================
// Rendering Helpers
// ============================================================================

void DisplayManager::renderCurrentItem() {
    if (!display_ || !dataProvider_) return;

    auto items = dataProvider_->getDisplayableItems();
    if (items.empty()) {
        display_->clear();
        display_->setCursor(0, 0);
        display_->print("No devices");
        return;
    }

    // Ensure index is valid
    if (currentItemIndex_ >= items.size()) {
        currentItemIndex_ = 0;
    }

    const DisplayItem& item = items[currentItemIndex_];

    // Dispatch to layout-specific renderer
    if (config_.layout == DisplayLayout::LAYOUT_16x2) {
        renderItem16x2(item, currentItemIndex_, items.size());
    } else {
        renderItem20x4(item, currentItemIndex_, items.size());
    }
}

void DisplayManager::renderErrorMessage(const String& message) {
    if (!display_) return;

    // Dispatch to layout-specific error renderer
    if (config_.layout == DisplayLayout::LAYOUT_16x2) {
        renderError16x2(message);
    } else {
        renderError20x4(message);
    }

    LOG_WARN(TAG, "Display error mode: %s", message.c_str());
}

// ============================================================================
// Layout-Specific Rendering: 16x2
// ============================================================================

void DisplayManager::renderItem16x2(const DisplayItem& item, size_t currentIndex, size_t totalItems) {
    display_->clear();

    // Row 0: [DeviceName]  [1/5]
    display_->setCursor(0, 0);
    String counter = String(currentIndex + 1) + "/" + String(totalItems);
    uint8_t maxNameLen = 16 - counter.length() - 1;  // Leave space for counter

    // Use scrolled name if too long
    String name = getScrolledName(item.deviceName, maxNameLen);
    display_->print(name);

    // Right-align counter on row 0
    display_->setCursor(16 - counter.length(), 0);
    display_->print(counter);

    // Row 1: [Value][Unit] [Type]
    display_->setCursor(0, 1);
    String valueLine = item.value;
    if (!item.unit.isEmpty()) {
        valueLine += item.unit;
    }

    // Reserve space for type on right side
    size_t typeLen = item.deviceType.length();
    size_t maxValueLen = 16 - typeLen - 1;
    if (valueLine.length() > maxValueLen) {
        valueLine = valueLine.substring(0, maxValueLen);
    }
    display_->print(valueLine);

    // Right-align type on row 1
    display_->setCursor(16 - typeLen, 1);
    display_->print(item.deviceType);
}

void DisplayManager::renderError16x2(const String& message) {
    display_->clear();

    // Row 0: ERROR
    display_->setCursor(0, 0);
    display_->print("ERROR");

    // Row 1: Error message (truncated to 16 chars)
    display_->setCursor(0, 1);
    String msg = message;
    if (msg.length() > 16) {
        msg = msg.substring(0, 16);
    }
    display_->print(msg);
}

// ============================================================================
// Layout-Specific Rendering: 20x4
// ============================================================================

void DisplayManager::renderItem20x4(const DisplayItem& item, size_t currentIndex, size_t totalItems) {
    display_->clear();

    // Row 0: Device name and type
    display_->setCursor(0, 0);
    uint8_t maxNameLen = 20 - item.deviceType.length() - 1;  // Leave space for type

    // Use scrolled name if too long
    String header = getScrolledName(item.deviceName, maxNameLen);
    display_->print(header);

    // Right-align type indicator on row 0
    display_->setCursor(20 - item.deviceType.length(), 0);
    display_->print(item.deviceType);

    // Row 1: Value with unit
    display_->setCursor(0, 1);
    String valueLine = item.value;
    if (!item.unit.isEmpty()) {
        valueLine += " " + item.unit;
    }
    display_->print(valueLine);

    // Row 2: For actuators, show state
    if (item.isActuator) {
        display_->setCursor(0, 2);
        display_->print("State: " + item.value);
    }

    // Row 3: Item counter (e.g., "1/5")
    display_->setCursor(0, 3);
    String counter = String(currentIndex + 1) + "/" + String(totalItems);
    display_->print(counter);
}

void DisplayManager::renderError20x4(const String& message) {
    display_->clear();

    // Row 0: Center "ERROR"
    display_->setCursor(0, 0);
    display_->print("*** ERROR ***");

    // Row 1: Error message
    display_->setCursor(0, 1);
    String msg = message;
    if (msg.length() > 20) {
        msg = msg.substring(0, 20);
    }
    display_->print(msg);

    // Row 2: Reconnecting message
    display_->setCursor(0, 2);
    display_->print("Reconnecting...");
}

void DisplayManager::rotateToNextItem() {
    if (!dataProvider_) return;

    auto items = dataProvider_->getDisplayableItems();
    if (items.empty()) {
        currentItemIndex_ = 0;
        return;
    }

    currentItemIndex_ = (currentItemIndex_ + 1) % items.size();
}

// ============================================================================
// Scroll Helpers
// ============================================================================

void DisplayManager::resetScroll() {
    scrollPosition_ = 0;
    lastScrollUpdate_ = millis();
    currentDeviceName_ = "";
    scrollPaused_ = true;
    scrollPauseCount_ = 0;
}

void DisplayManager::updateScroll(const String& fullName, uint8_t maxLen) {
    // Check if device changed - reset scroll if so
    if (currentDeviceName_ != fullName) {
        currentDeviceName_ = fullName;
        scrollPosition_ = 0;
        scrollPaused_ = true;
        scrollPauseCount_ = 0;
        return;
    }

    // Handle initial pause before scrolling starts
    if (scrollPaused_) {
        scrollPauseCount_++;
        if (scrollPauseCount_ >= SCROLL_PAUSE_CYCLES) {
            scrollPaused_ = false;
        }
        return;
    }

    // Create scrolling text with separator for looping effect
    // e.g., "LongDeviceName | LongDeviceName | ..."
    String scrollText = fullName + SCROLL_SEPARATOR + fullName;
    size_t scrollLen = fullName.length() + strlen(SCROLL_SEPARATOR);

    // Advance scroll position
    scrollPosition_++;

    // Reset when we've scrolled through one complete cycle
    if (scrollPosition_ >= scrollLen) {
        scrollPosition_ = 0;
        scrollPaused_ = true;  // Pause at start of each loop
        scrollPauseCount_ = 0;
    }
}

String DisplayManager::getScrolledName(const String& fullName, uint8_t maxLen) {
    // If name fits, return as-is (padded to maxLen for clean display)
    if (fullName.length() <= maxLen) {
        return fullName;
    }

    // Create scrolling text with separator
    String scrollText = fullName + SCROLL_SEPARATOR + fullName;

    // Extract visible portion based on scroll position
    String visible = scrollText.substring(scrollPosition_, scrollPosition_ + maxLen);

    // Ensure we always return exactly maxLen characters
    while (visible.length() < maxLen) {
        visible += " ";
    }

    return visible;
}
