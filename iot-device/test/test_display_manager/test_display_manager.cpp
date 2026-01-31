//
// test_display_manager.cpp - Unit tests for DisplayManager
//

#include <Arduino.h>
#include <unity.h>
#include <SPIFFS.h>
#include <DisplayManager.h>
#include "MockDisplay.h"
#include "MockDataProvider.h"

// ----------------------------------------------------------------------------
// Helper Functions
// ----------------------------------------------------------------------------

void create_config_file(const char* filename, const char* content) {
    if (SPIFFS.exists(filename)) {
        SPIFFS.remove(filename);
    }
    File f = SPIFFS.open(filename, "w");
    if (f) {
        f.print(content);
        f.close();
    } else {
        TEST_FAIL_MESSAGE("Failed to create config file in SPIFFS");
    }
}

// ----------------------------------------------------------------------------
// Setup and Teardown
// ----------------------------------------------------------------------------

void setUp(void) {
    if (!SPIFFS.begin(true)) {
        TEST_FAIL_MESSAGE("SPIFFS Mount Failed");
    }
}

void tearDown(void) {
    // Cleanup test config files
}

// ----------------------------------------------------------------------------
// Configuration Tests
// ----------------------------------------------------------------------------

void test_load_valid_config() {
    const char* filename = "/test_display_valid.json";
    const char* json = R"({
        "enabled": true,
        "type": "I2C_LCD",
        "i2c_address": "0x3F",
        "cols": 16,
        "rows": 2,
        "rotationInterval": 5000,
        "sda": 21,
        "scl": 22
    })";
    
    create_config_file(filename, json);
    
    bool result = DisplayManager::loadConfig(filename);
    TEST_ASSERT_TRUE_MESSAGE(result, "loadConfig should return true for valid JSON");
    
    const DisplayConfig& config = DisplayManager::getInstance().getConfig();
    TEST_ASSERT_TRUE_MESSAGE(config.enabled, "enabled should be true");
    TEST_ASSERT_EQUAL_STRING("I2C_LCD", config.type.c_str());
    TEST_ASSERT_EQUAL_HEX8(0x3F, config.i2cAddress);
    TEST_ASSERT_EQUAL(16, config.cols);
    TEST_ASSERT_EQUAL(2, config.rows);
    TEST_ASSERT_EQUAL(5000, config.rotationInterval);
}

void test_load_config_with_numeric_address() {
    const char* filename = "/test_display_num.json";
    const char* json = R"({
        "enabled": true,
        "type": "I2C_LCD",
        "i2c_address": 39,
        "cols": 20,
        "rows": 4
    })";
    
    create_config_file(filename, json);
    
    bool result = DisplayManager::loadConfig(filename);
    TEST_ASSERT_TRUE_MESSAGE(result, "loadConfig should accept numeric address");
    
    const DisplayConfig& config = DisplayManager::getInstance().getConfig();
    TEST_ASSERT_EQUAL_HEX8(39, config.i2cAddress);
}

void test_load_missing_config_disables_display() {
    bool result = DisplayManager::loadConfig("/nonexistent_display.json");
    // Missing config should return true (display is optional) but disable it
    TEST_ASSERT_TRUE_MESSAGE(result, "Missing config should return true");
    
    const DisplayConfig& config = DisplayManager::getInstance().getConfig();
    TEST_ASSERT_FALSE_MESSAGE(config.enabled, "Display should be disabled when config is missing");
}

void test_load_config_with_defaults() {
    const char* filename = "/test_display_defaults.json";
    const char* json = R"({"enabled": true})";
    
    create_config_file(filename, json);
    
    bool result = DisplayManager::loadConfig(filename);
    TEST_ASSERT_TRUE(result);
    
    const DisplayConfig& config = DisplayManager::getInstance().getConfig();
    // Check defaults are applied
    TEST_ASSERT_EQUAL_STRING("I2C_LCD", config.type.c_str());
    TEST_ASSERT_EQUAL_HEX8(0x27, config.i2cAddress);
    TEST_ASSERT_EQUAL(20, config.cols);
    TEST_ASSERT_EQUAL(4, config.rows);
    TEST_ASSERT_EQUAL(3000, config.rotationInterval);
    TEST_ASSERT_EQUAL(21, config.sdaPin);
    TEST_ASSERT_EQUAL(22, config.sclPin);
}

void test_disabled_display_returns_early() {
    const char* filename = "/test_display_disabled.json";
    const char* json = R"({"enabled": false})";
    
    create_config_file(filename, json);
    
    bool result = DisplayManager::loadConfig(filename);
    TEST_ASSERT_TRUE(result);
    
    TEST_ASSERT_FALSE_MESSAGE(DisplayManager::isEnabled(), 
        "isEnabled should return false when display is disabled");
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------

void setup() {
    delay(2000); // Wait for Serial to stabilize
    
    UNITY_BEGIN();
    
    // Configuration tests
    RUN_TEST(test_load_valid_config);
    RUN_TEST(test_load_config_with_numeric_address);
    RUN_TEST(test_load_missing_config_disables_display);
    RUN_TEST(test_load_config_with_defaults);
    RUN_TEST(test_disabled_display_returns_early);
    
    UNITY_END();
}

void loop() {
    // Empty
}

