//
// DisplayManager.h - Singleton manager for display operations
//

#pragma once

#include <Arduino.h>
#include <memory>
#include "IDisplay.h"
#include "IDisplayDataProvider.h"

/**
 * @brief Supported display layout types.
 *
 * Each layout has optimized rendering for its dimensions.
 */
enum class DisplayLayout {
    LAYOUT_16x2,    // 16 columns x 2 rows
    LAYOUT_20x4     // 20 columns x 4 rows (default)
};

/**
 * @brief Configuration for DisplayManager loaded from JSON.
 */
struct DisplayConfig {
    bool enabled;                    // Whether display is enabled
    String type;                     // Display type (e.g., "I2C_LCD")
    DisplayLayout layout;            // Display layout (16x2, 20x4)
    uint8_t i2cAddress;              // I2C address for I2C displays
    unsigned long rotationInterval;  // Interval between item rotations (ms)
    unsigned long scrollSpeed;       // Scroll speed in ms per character shift
    int sdaPin;                      // I2C SDA pin
    int sclPin;                      // I2C SCL pin

    DisplayConfig()
        : enabled(false)
        , type("I2C_LCD")
        , layout(DisplayLayout::LAYOUT_20x4)
        , i2cAddress(0x27)
        , rotationInterval(3000)
        , scrollSpeed(400)
        , sdaPin(21)
        , sclPin(22)
    {}

    // Helper methods to get dimensions from layout
    uint8_t getCols() const {
        return (layout == DisplayLayout::LAYOUT_16x2) ? 16 : 20;
    }

    uint8_t getRows() const {
        return (layout == DisplayLayout::LAYOUT_16x2) ? 2 : 4;
    }

    // Get max name length before scrolling is needed
    uint8_t getMaxNameLength() const {
        // 16x2: counter takes ~4 chars (e.g., "1/10"), leave 1 space -> 11 chars for name
        // 20x4: type takes ~5 chars (e.g., "DS18B"), leave 1 space -> 14 chars for name
        return (layout == DisplayLayout::LAYOUT_16x2) ? 11 : 14;
    }
};

/**
 * @brief Singleton manager for display operations.
 * 
 * Responsibilities:
 * - Load display configuration from JSON
 * - Initialize the appropriate display hardware
 * - Rotate through displayable items at configured interval
 * - Show error messages when WiFi/MQTT connection is lost
 * 
 * Follows the same Singleton pattern as MqttManager for consistency.
 */
class DisplayManager {
public:
    // Singleton Access
    static DisplayManager& getInstance();

    // Prevent copy/move
    DisplayManager(const DisplayManager&) = delete;
    DisplayManager& operator=(const DisplayManager&) = delete;

    /**
     * @brief Loads display configuration from a JSON file.
     * @param filename Path to the configuration file
     * @return true if configuration loaded successfully
     */
    static bool loadConfig(const char* filename);

    /**
     * @brief Initializes the display with the loaded configuration.
     * @param dataProvider Pointer to the data provider (ownership transferred)
     * @return true if initialization succeeded
     */
    static bool init(std::unique_ptr<IDisplayDataProvider> dataProvider);

    /**
     * @brief Main update loop - call from Arduino loop().
     * 
     * Handles:
     * - Checking connection status
     * - Rotating display items at configured interval
     * - Refreshing data from provider
     */
    static void update();

    /**
     * @brief Checks if the display is enabled and initialized.
     * @return true if display is ready for use
     */
    static bool isEnabled();

    /**
     * @brief Gets the current configuration.
     * @return Reference to the display configuration
     */
    const DisplayConfig& getConfig() const { return config_; }

private:
    DisplayManager() = default;
    ~DisplayManager() = default;

    // Display state
    std::unique_ptr<IDisplay> display_;
    std::unique_ptr<IDisplayDataProvider> dataProvider_;
    DisplayConfig config_;
    bool initialized_;

    // Rotation state
    size_t currentItemIndex_;
    unsigned long lastRotation_;
    unsigned long lastDataRefresh_;
    bool inErrorMode_;

    // Scroll state for long device names
    size_t scrollPosition_;              // Current scroll offset
    unsigned long lastScrollUpdate_;     // Last time scroll was updated
    String currentDeviceName_;           // Cached name for scroll tracking
    bool scrollPaused_;                  // Initial pause before scrolling
    uint8_t scrollPauseCount_;           // Counter for initial pause

    // Timing constants
    static constexpr unsigned long DATA_REFRESH_INTERVAL = 1000;  // Refresh data every 1s
    static constexpr uint8_t SCROLL_PAUSE_CYCLES = 3;             // Pause cycles before scroll starts
    static constexpr const char* SCROLL_SEPARATOR = " | ";        // Separator for looping scroll

    // Internal helpers
    void renderCurrentItem();
    void renderErrorMessage(const String& message);
    void rotateToNextItem();
    void resetScroll();

    // Scroll helpers
    String getScrolledName(const String& fullName, uint8_t maxLen);
    void updateScroll(const String& fullName, uint8_t maxLen);

    // Layout-specific rendering methods
    void renderItem16x2(const DisplayItem& item, size_t currentIndex, size_t totalItems);
    void renderItem20x4(const DisplayItem& item, size_t currentIndex, size_t totalItems);
    void renderError16x2(const String& message);
    void renderError20x4(const String& message);

    // Utility to parse layout string from config
    static DisplayLayout parseLayout(const String& layoutStr);
};

