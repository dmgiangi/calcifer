#include <Arduino.h>
#include <unity.h>
#include <MqttManager.h>
#include <SPIFFS.h>
#include <PinConfig.h>

// Helper to write a clean config file to SPIFFS
void create_config_file(const char* filename, const char* content) {
    if (SPIFFS.exists(filename)) {
        SPIFFS.remove(filename);
    }
    File f = SPIFFS.open(filename, "w");
    if (f) {
        f.print(content);
        f.close();
    } else {
        TEST_FAIL_MESSAGE("Failed to create mock config file in SPIFFS");
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
    // Optional cleanup
}

// ----------------------------------------------------------------------------
// Test Cases
// ----------------------------------------------------------------------------

void test_load_valid_config() {
    const char* filename = "/test_mqtt_valid.json";
    const char* json = "{\"host\":\"192.168.1.50\",\"port\":1884,\"clientId\":\"TestClient\",\"username\":\"user\",\"password\":\"pass\"}";
    
    create_config_file(filename, json);

    MqttManager& manager = MqttManager::getInstance();
    bool result = manager.loadConfig(filename);

    TEST_ASSERT_TRUE_MESSAGE(result, "loadConfig should return true for valid JSON");
    TEST_ASSERT_EQUAL_STRING("192.168.1.50", manager.getMqttHost().c_str());
    TEST_ASSERT_EQUAL(1884, manager.getMqttPort());
    TEST_ASSERT_EQUAL_STRING("TestClient", manager.getClientId().c_str());
}

void test_load_missing_config() {
    MqttManager& manager = MqttManager::getInstance();
    bool result = manager.loadConfig("/non_existent.json");
    TEST_ASSERT_FALSE_MESSAGE(result, "loadConfig should return false for missing file");
}

void test_register_pins_static() {
    std::vector<PinConfig> pins;

    // Add a digital input (Using Pin 13 which is valid)
    PinConfig p1;
    p1.pin = 13; 
    p1.mode = INPUT_DIGITAL;
    p1.name = "TestInput";
    p1.pollingInterval = 1000;
    pins.push_back(p1);

    // Add a PWM output (Using Pin 25 which is valid)
    PinConfig p2;
    p2.pin = 25;
    p2.mode = PWM;
    p2.name = "TestPWM";
    p2.defaultState = 128;
    pins.push_back(p2);

    // Use static method
    bool result = MqttManager::registerPins(pins);
    TEST_ASSERT_TRUE_MESSAGE(result, "registerPins should return true");
}

void test_register_ds18b20() {
    std::vector<PinConfig> pins;
    
    // Use Pin 22 which is flagged as 1-Wire capable in PinConfig
    PinConfig p;
    p.pin = 22; 
    p.mode = DS18B20;
    p.name = "TestDallas";
    p.pollingInterval = 2000;
    pins.push_back(p);

    bool result = MqttManager::registerPins(pins);
    TEST_ASSERT_TRUE_MESSAGE(result, "Should register DS18B20 without error");
}

void test_register_thermocouple() {
    std::vector<PinConfig> pins;
    
    // Use the pins configured earlier: CS: 23, SCK: 18, SO: 19
    PinConfig p;
    p.pin = 23;        // CS
    p.pinClock = 18;   // SCK
    p.pinData = 19;    // SO
    p.mode = THERMOCOUPLE;
    p.name = "TestThermo";
    p.pollingInterval = 1000;
    pins.push_back(p);

    bool result = MqttManager::registerPins(pins);
    TEST_ASSERT_TRUE_MESSAGE(result, "Should register THERMOCOUPLE without error");
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------

void setup() {
    delay(2000); // Wait for Serial to stabilize
    
    UNITY_BEGIN();
    
    RUN_TEST(test_load_valid_config);
    RUN_TEST(test_load_missing_config);
    RUN_TEST(test_register_pins_static);
    RUN_TEST(test_register_ds18b20);
    RUN_TEST(test_register_thermocouple);
    
    UNITY_END();
}

void loop() {
    // Empty
}
