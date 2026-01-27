//
// test_fan_handler.cpp - Unit tests for FanHandler
//
// Tests the mqttToState() and stateToMqtt() functions for 3-relay fan control.
// The fan uses 5 discrete speed states (0-4) controlled by 3 relays.
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
// Test Cases: mqttToState - Boundary Values
// ----------------------------------------------------------------------------

void test_mqtt_to_state_zero() {
    // MQTT 0 should map to state 0 (OFF)
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mqttToState(0));
}

void test_mqtt_to_state_one() {
    // MQTT 1 should map to state 1 (lowest speed)
    TEST_ASSERT_EQUAL_UINT8(1, FanHandler::mqttToState(1));
}

void test_mqtt_to_state_25() {
    // MQTT 25 should map to state 1 (upper boundary of state 1)
    TEST_ASSERT_EQUAL_UINT8(1, FanHandler::mqttToState(25));
}

void test_mqtt_to_state_26() {
    // MQTT 26 should map to state 2 (lower boundary of state 2)
    TEST_ASSERT_EQUAL_UINT8(2, FanHandler::mqttToState(26));
}

void test_mqtt_to_state_50() {
    // MQTT 50 should map to state 2 (upper boundary of state 2)
    TEST_ASSERT_EQUAL_UINT8(2, FanHandler::mqttToState(50));
}

void test_mqtt_to_state_51() {
    // MQTT 51 should map to state 3 (lower boundary of state 3)
    TEST_ASSERT_EQUAL_UINT8(3, FanHandler::mqttToState(51));
}

void test_mqtt_to_state_75() {
    // MQTT 75 should map to state 3 (upper boundary of state 3)
    TEST_ASSERT_EQUAL_UINT8(3, FanHandler::mqttToState(75));
}

void test_mqtt_to_state_76() {
    // MQTT 76 should map to state 4 (lower boundary of state 4)
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(76));
}

void test_mqtt_to_state_100() {
    // MQTT 100 should map to state 4 (highest speed)
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(100));
}

// ----------------------------------------------------------------------------
// Test Cases: mqttToState - Edge Cases
// ----------------------------------------------------------------------------

void test_mqtt_to_state_negative() {
    // Negative values should map to state 0
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mqttToState(-1));
    TEST_ASSERT_EQUAL_UINT8(0, FanHandler::mqttToState(-100));
}

void test_mqtt_to_state_over_100() {
    // Values > 100 should map to state 4
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(101));
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(255));
    TEST_ASSERT_EQUAL_UINT8(4, FanHandler::mqttToState(1000));
}

// ----------------------------------------------------------------------------
// Test Cases: stateToMqtt - Valid States
// ----------------------------------------------------------------------------

void test_state_to_mqtt_0() {
    // State 0 should return MQTT 0
    TEST_ASSERT_EQUAL_INT(0, FanHandler::stateToMqtt(0));
}

void test_state_to_mqtt_1() {
    // State 1 should return MQTT 25
    TEST_ASSERT_EQUAL_INT(25, FanHandler::stateToMqtt(1));
}

void test_state_to_mqtt_2() {
    // State 2 should return MQTT 50
    TEST_ASSERT_EQUAL_INT(50, FanHandler::stateToMqtt(2));
}

void test_state_to_mqtt_3() {
    // State 3 should return MQTT 75
    TEST_ASSERT_EQUAL_INT(75, FanHandler::stateToMqtt(3));
}

void test_state_to_mqtt_4() {
    // State 4 should return MQTT 100
    TEST_ASSERT_EQUAL_INT(100, FanHandler::stateToMqtt(4));
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
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(0, state);
}

void test_roundtrip_state_1() {
    // State 1 -> MQTT 25 -> State 1
    int mqtt = FanHandler::stateToMqtt(1);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(1, state);
}

void test_roundtrip_state_2() {
    // State 2 -> MQTT 50 -> State 2
    int mqtt = FanHandler::stateToMqtt(2);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(2, state);
}

void test_roundtrip_state_3() {
    // State 3 -> MQTT 75 -> State 3
    int mqtt = FanHandler::stateToMqtt(3);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(3, state);
}

void test_roundtrip_state_4() {
    // State 4 -> MQTT 100 -> State 4
    int mqtt = FanHandler::stateToMqtt(4);
    uint8_t state = FanHandler::mqttToState(mqtt);
    TEST_ASSERT_EQUAL_UINT8(4, state);
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------

void setup() {
    delay(2000); // Wait for Serial to stabilize

    UNITY_BEGIN();

    // mqttToState - Boundary values
    RUN_TEST(test_mqtt_to_state_zero);
    RUN_TEST(test_mqtt_to_state_one);
    RUN_TEST(test_mqtt_to_state_25);
    RUN_TEST(test_mqtt_to_state_26);
    RUN_TEST(test_mqtt_to_state_50);
    RUN_TEST(test_mqtt_to_state_51);
    RUN_TEST(test_mqtt_to_state_75);
    RUN_TEST(test_mqtt_to_state_76);
    RUN_TEST(test_mqtt_to_state_100);

    // mqttToState - Edge cases
    RUN_TEST(test_mqtt_to_state_negative);
    RUN_TEST(test_mqtt_to_state_over_100);

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
