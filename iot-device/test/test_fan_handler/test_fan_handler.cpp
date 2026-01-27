//
// test_fan_handler.cpp - Unit tests for FanHandler
//
// Tests the mqttToState() and stateToMqtt() functions for 3-relay fan control.
// The fan uses 5 discrete speed states (0-4) controlled by 3 relays.
// MQTT API accepts values 0-4 directly (no percentage mapping).
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
// Test Cases: mqttToState - Valid Values (0-4)
// ----------------------------------------------------------------------------

void test_mqtt_to_state_0() {
    // MQTT 0 should map to state 0 (OFF)
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mqttToState(0));
}

void test_mqtt_to_state_1() {
    // MQTT 1 should map to state 1 (lowest speed)
    TEST_ASSERT_EQUAL_UINT8(1, FanHandler::mqttToState(1));
}

void test_mqtt_to_state_2() {
    // MQTT 2 should map to state 2 (medium-low speed)
    TEST_ASSERT_EQUAL_UINT8(2, FanHandler::mqttToState(2));
}

void test_mqtt_to_state_3() {
    // MQTT 3 should map to state 3 (medium-high speed)
    TEST_ASSERT_EQUAL_UINT8(3, FanHandler::mqttToState(3));
}

void test_mqtt_to_state_4() {
    // MQTT 4 should map to state 4 (highest speed)
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(4));
}

// ----------------------------------------------------------------------------
// Test Cases: mqttToState - Edge Cases (out of range)
// ----------------------------------------------------------------------------

void test_mqtt_to_state_negative() {
    // Negative values should be constrained to state 0
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mqttToState(-1));
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mqttToState(-100));
}

void test_mqtt_to_state_over_4() {
    // Values > 4 should be constrained to state 4
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(5));
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(100));
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(255));
}

// ----------------------------------------------------------------------------
// Test Cases: stateToMqtt - Valid States (0-4)
// ----------------------------------------------------------------------------

void test_state_to_mqtt_0() {
    // State 0 should return MQTT 0
    TEST_ASSERT_EQUAL_INT(0, FanHandler::stateToMqtt(0));
}

void test_state_to_mqtt_1() {
    // State 1 should return MQTT 1
    TEST_ASSERT_EQUAL_INT(1, FanHandler::stateToMqtt(1));
}

void test_state_to_mqtt_2() {
    // State 2 should return MQTT 2
    TEST_ASSERT_EQUAL_INT(2, FanHandler::stateToMqtt(2));
}

void test_state_to_mqtt_3() {
    // State 3 should return MQTT 3
    TEST_ASSERT_EQUAL_INT(3, FanHandler::stateToMqtt(3));
}

void test_state_to_mqtt_4() {
    // State 4 should return MQTT 4
    TEST_ASSERT_EQUAL_INT(4, FanHandler::stateToMqtt(4));
}

// ----------------------------------------------------------------------------
// Test Cases: stateToMqtt - Invalid States
// ----------------------------------------------------------------------------

void test_state_to_mqtt_invalid() {
    // Invalid states (> 4) should return 0
    TEST_ASSERT_EQUAL_INT(0, FanHandler::stateToMqtt(5));
    TEST_ASSERT_EQUAL_INT(0, FanHandler::stateToMqtt(10));
    TEST_ASSERT_EQUAL_INT(0, FanHandler::stateToMqtt(255));
}

// ----------------------------------------------------------------------------
// Test Cases: Round-trip Consistency
// ----------------------------------------------------------------------------

void test_roundtrip_state_0() {
    // State 0 -> MQTT 0 -> State 0
    int mqtt = FanHandler::stateToMqtt(0);
    TEST_ASSERT_EQUAL_INT(0, mqtt);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(0, state);
}

void test_roundtrip_state_1() {
    // State 1 -> MQTT 1 -> State 1
    int mqtt = FanHandler::stateToMqtt(1);
    TEST_ASSERT_EQUAL_INT(1, mqtt);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(1, state);
}

void test_roundtrip_state_2() {
    // State 2 -> MQTT 2 -> State 2
    int mqtt = FanHandler::stateToMqtt(2);
    TEST_ASSERT_EQUAL_INT(2, mqtt);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(2, state);
}

void test_roundtrip_state_3() {
    // State 3 -> MQTT 3 -> State 3
    int mqtt = FanHandler::stateToMqtt(3);
    TEST_ASSERT_EQUAL_INT(3, mqtt);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(3, state);
}

void test_roundtrip_state_4() {
    // State 4 -> MQTT 4 -> State 4
    int mqtt = FanHandler::stateToMqtt(4);
    TEST_ASSERT_EQUAL_INT(4, mqtt);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(4, state);
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------

void setup() {
    delay(2000); // Wait for Serial to stabilize

    UNITY_BEGIN();

    // mqttToState - Valid values (0-4)
    RUN_TEST(test_mqtt_to_state_0);
    RUN_TEST(test_mqtt_to_state_1);
    RUN_TEST(test_mqtt_to_state_2);
    RUN_TEST(test_mqtt_to_state_3);
    RUN_TEST(test_mqtt_to_state_4);

    // mqttToState - Edge cases
    RUN_TEST(test_mqtt_to_state_negative);
    RUN_TEST(test_mqtt_to_state_over_4);

    // stateToMqtt - Valid states
    RUN_TEST(test_state_to_mqtt_0);
    RUN_TEST(test_state_to_mqtt_1);
    RUN_TEST(test_state_to_mqtt_2);
    RUN_TEST(test_state_to_mqtt_3);
    RUN_TEST(test_state_to_mqtt_4);

    // stateToMqtt - Invalid states
    RUN_TEST(test_state_to_mqtt_invalid);

    // Round-trip consistency
    RUN_TEST(test_roundtrip_state_0);
    RUN_TEST(test_roundtrip_state_1);
    RUN_TEST(test_roundtrip_state_2);
    RUN_TEST(test_roundtrip_state_3);
    RUN_TEST(test_roundtrip_state_4);

    UNITY_END();
}

void loop() {
    // Empty
}
