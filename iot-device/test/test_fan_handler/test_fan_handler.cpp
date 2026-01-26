//
// test_fan_handler.cpp - Unit tests for FanHandler
//
// Tests the mapToDimmerLevel() function which maps MQTT values (0-100)
// to dimmer levels (minPwm-100) with proper rounding.
//

#include <Arduino.h>
#include <unity.h>
#include <handlers/FanHandler.h>

// ----------------------------------------------------------------------------
// Setup and Teardown
// ----------------------------------------------------------------------------

void setUp(void) {
    // No setup needed for pure function tests
}

void tearDown(void) {
    // No teardown needed
}

// ----------------------------------------------------------------------------
// Test Cases: mapToDimmerLevel - Edge Cases
// ----------------------------------------------------------------------------

void test_map_zero_returns_zero() {
    // Value 0 should always return 0 (OFF state)
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mapToDimmerLevel(0, 0));
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mapToDimmerLevel(0, 25));
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mapToDimmerLevel(0, 40));
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mapToDimmerLevel(0, 50));
}

void test_map_negative_returns_zero() {
    // Negative values should return 0
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mapToDimmerLevel(-1, 40));
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mapToDimmerLevel(-100, 40));
}

void test_map_one_returns_minPwm() {
    // Value 1 should return exactly minPwm
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mapToDimmerLevel(1, 0));
    TEST_ASSERT_EQUAL_UINT8(25, FanHandler::mapToDimmerLevel(1, 25));
    TEST_ASSERT_EQUAL_UINT8(40, FanHandler::mapToDimmerLevel(1, 40));
    TEST_ASSERT_EQUAL_UINT8(50, FanHandler::mapToDimmerLevel(1, 50));
}

void test_map_hundred_returns_hundred() {
    // Value 100 should always return 100 (max dimmer level)
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(100, 0));
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(100, 25));
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(100, 40));
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(100, 50));
}

void test_map_over_hundred_returns_hundred() {
    // Values > 100 should be clamped to 100
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(101, 40));
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(255, 40));
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(1000, 40));
}

// ----------------------------------------------------------------------------
// Test Cases: mapToDimmerLevel - Linear Interpolation
// ----------------------------------------------------------------------------

void test_map_midpoint_with_minPwm_zero() {
    // With minPwm=0, MQTT 50 should map to ~50%
    // Formula: 0 + (50-1) * 100 / 99 = 49.49... rounds to 49 or 50
    uint8_t result = FanHandler::mapToDimmerLevel(50, 0);
    TEST_ASSERT_UINT8_WITHIN(1, 50, result);
}

void test_map_midpoint_with_minPwm_40() {
    // With minPwm=40, MQTT 50 should map to ~70%
    // Range is 40-100 (60 levels), midpoint of MQTT 1-100 is ~50
    // Formula: 40 + (50-1) * 60 / 99 = 40 + 2940/99 = 40 + 29.7 ≈ 70
    uint8_t result = FanHandler::mapToDimmerLevel(50, 40);
    TEST_ASSERT_UINT8_WITHIN(1, 70, result);
}

void test_map_quarter_with_minPwm_40() {
    // With minPwm=40, MQTT 25 should map to ~55%
    // Formula: 40 + (25-1) * 60 / 99 = 40 + 1440/99 = 40 + 14.5 ≈ 55
    uint8_t result = FanHandler::mapToDimmerLevel(25, 40);
    TEST_ASSERT_UINT8_WITHIN(1, 55, result);
}

void test_map_three_quarters_with_minPwm_40() {
    // With minPwm=40, MQTT 75 should map to ~85%
    // Formula: 40 + (75-1) * 60 / 99 = 40 + 4440/99 = 40 + 44.8 ≈ 85
    uint8_t result = FanHandler::mapToDimmerLevel(75, 40);
    TEST_ASSERT_UINT8_WITHIN(1, 85, result);
}

// ----------------------------------------------------------------------------
// Test Cases: mapToDimmerLevel - Monotonicity
// ----------------------------------------------------------------------------

void test_map_is_monotonically_increasing() {
    // Verify that increasing MQTT values produce non-decreasing dimmer levels
    int minPwm = 40;
    uint8_t prevLevel = 0;
    
    for (int mqttValue = 0; mqttValue <= 100; mqttValue++) {
        uint8_t level = FanHandler::mapToDimmerLevel(mqttValue, minPwm);
        TEST_ASSERT_GREATER_OR_EQUAL_UINT8(prevLevel, level);
        prevLevel = level;
    }
}

void test_map_covers_full_range() {
    // Verify that MQTT 1 maps to minPwm and MQTT 100 maps to 100
    int minPwm = 40;
    
    uint8_t minLevel = FanHandler::mapToDimmerLevel(1, minPwm);
    uint8_t maxLevel = FanHandler::mapToDimmerLevel(100, minPwm);
    
    TEST_ASSERT_EQUAL_UINT8(minPwm, minLevel);
    TEST_ASSERT_EQUAL_UINT8(100, maxLevel);
}

// ----------------------------------------------------------------------------
// Test Cases: mapToDimmerLevel - Various minPwm Values
// ----------------------------------------------------------------------------

void test_map_with_minPwm_25() {
    // Test with minPwm=25 (75 level range)
    TEST_ASSERT_EQUAL_UINT8(25, FanHandler::mapToDimmerLevel(1, 25));
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(100, 25));
    
    // Midpoint: 25 + (50-1) * 75 / 99 ≈ 25 + 37 = 62
    uint8_t mid = FanHandler::mapToDimmerLevel(50, 25);
    TEST_ASSERT_UINT8_WITHIN(1, 62, mid);
}

void test_map_with_minPwm_50() {
    // Test with minPwm=50 (50 level range)
    TEST_ASSERT_EQUAL_UINT8(50, FanHandler::mapToDimmerLevel(1, 50));
    TEST_ASSERT_EQUAL_UINT8(100, FanHandler::mapToDimmerLevel(100, 50));
    
    // Midpoint: 50 + (50-1) * 50 / 99 ≈ 50 + 25 = 75
    uint8_t mid = FanHandler::mapToDimmerLevel(50, 50);
    TEST_ASSERT_UINT8_WITHIN(1, 75, mid);
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------

void setup() {
    delay(2000); // Wait for Serial to stabilize
    
    UNITY_BEGIN();
    
    // Edge cases
    RUN_TEST(test_map_zero_returns_zero);
    RUN_TEST(test_map_negative_returns_zero);
    RUN_TEST(test_map_one_returns_minPwm);
    RUN_TEST(test_map_hundred_returns_hundred);
    RUN_TEST(test_map_over_hundred_returns_hundred);
    
    // Linear interpolation
    RUN_TEST(test_map_midpoint_with_minPwm_zero);
    RUN_TEST(test_map_midpoint_with_minPwm_40);
    RUN_TEST(test_map_quarter_with_minPwm_40);
    RUN_TEST(test_map_three_quarters_with_minPwm_40);
    
    // Monotonicity and range
    RUN_TEST(test_map_is_monotonically_increasing);
    RUN_TEST(test_map_covers_full_range);
    
    // Various minPwm values
    RUN_TEST(test_map_with_minPwm_25);
    RUN_TEST(test_map_with_minPwm_50);
    
    UNITY_END();
}

void loop() {
    // Empty
}

