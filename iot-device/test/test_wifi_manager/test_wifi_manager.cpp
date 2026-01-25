#include <Arduino.h>
#include <unity.h>
#include <WiFiManager.h>
#include <SPIFFS.h>
#include <WiFi.h>

// Helper to write a config file to SPIFFS
void create_wifi_config_file(const char* filename, const char* content) {
    if (SPIFFS.exists(filename)) {
        SPIFFS.remove(filename);
    }
    File f = SPIFFS.open(filename, "w");
    if (f) {
        size_t written = f.print(content);
        f.flush();
        f.close();
        if (written != strlen(content)) {
            TEST_FAIL_MESSAGE("Failed to write full config content");
        }
    } else {
        TEST_FAIL_MESSAGE("Failed to open file for writing in SPIFFS");
    }
}

// ----------------------------------------------------------------------------
// Setup and Teardown
// ----------------------------------------------------------------------------

void setUp(void) {
    if (!SPIFFS.begin(true)) {
        TEST_FAIL_MESSAGE("SPIFFS Mount Failed");
    }
    // Disconnect WiFi before each test to ensure clean state
    WiFi.disconnect(true, true); 
    delay(500);
}

void tearDown(void) {
    if (SPIFFS.exists("/test_wifi.json")) SPIFFS.remove("/test_wifi.json");
    WiFi.disconnect(true, true);
}

// ----------------------------------------------------------------------------
// Test Cases
// ----------------------------------------------------------------------------

void test_wifi_config_missing_file() {
    // Should fail immediately because file doesn't exist
    bool result = connectToWiFi("/non_existent_wifi.json");
    TEST_ASSERT_FALSE_MESSAGE(result, "Should return false if config file is missing");
}

void test_wifi_config_empty_file() {
    create_wifi_config_file("/test_wifi.json", "");
    bool result = connectToWiFi("/test_wifi.json");
    TEST_ASSERT_FALSE_MESSAGE(result, "Should return false if config file is empty");
}

void test_wifi_config_invalid_json() {
    create_wifi_config_file("/test_wifi.json", "{ invalid json }");
    bool result = connectToWiFi("/test_wifi.json");
    TEST_ASSERT_FALSE_MESSAGE(result, "Should return false if JSON is invalid");
}

void test_wifi_config_missing_ssid() {
    // Valid JSON but no SSID
    const char* json = R"({
        "password": "pass",
        "useDhcp": true
    })";
    create_wifi_config_file("/test_wifi.json", json);
    
    bool result = connectToWiFi("/test_wifi.json");
    TEST_ASSERT_FALSE_MESSAGE(result, "Should return false if SSID is missing");
}

// NOTE: We cannot easily test successful WiFi connection in a unit test environment 
// without a real AP credentials or a mock WiFi library. 
// However, we can test that the function attempts to connect and fails (timeout) 
// or returns false if credentials are dummy.
// We'll set a very short timeout to avoid blocking the test for too long.

void test_wifi_connect_timeout() {
    // Provide dummy credentials and a short timeout
    const char* json = R"({
        "ssid": "DUMMY_SSID_TEST",
        "password": "DUMMY_PASSWORD",
        "useDhcp": true,
        "connectTimeout": 2000 
    })";
    create_wifi_config_file("/test_wifi.json", json);

    unsigned long start = millis();
    bool result = connectToWiFi("/test_wifi.json");
    unsigned long duration = millis() - start;

    TEST_ASSERT_FALSE_MESSAGE(result, "Should fail to connect to dummy AP");
    // Ensure it waited at least the timeout duration (approx)
    // Note: implementation might exit slightly earlier/later depending on logic, 
    // but duration should be close to 2000ms.
    TEST_ASSERT_GREATER_THAN(1500, duration); 
}

void test_wifi_static_ip_parsing() {
    // This test verifies that the code parses Static IP config. 
    // We can't easily assert internal state of WiFi.config() without mocking,
    // but we can ensure it doesn't crash and attempts connection.
    
    const char* json = R"({
        "ssid": "DUMMY_SSID",
        "password": "pass",
        "useDhcp": false,
        "ip": "192.168.1.100",
        "gateway": "192.168.1.1",
        "subnet": "255.255.255.0",
        "dns": "8.8.8.8",
        "connectTimeout": 1000
    })";
    create_wifi_config_file("/test_wifi.json", json);

    bool result = connectToWiFi("/test_wifi.json");
    TEST_ASSERT_FALSE(result); // Still expect fail due to dummy credentials
}

void test_wifi_static_ip_invalid_format() {
    // IP invalid, should fallback or fail (implementation logs error but continues with what it has, 
    // likely falling back to DHCP or failing config).
    const char* json = R"({
        "ssid": "DUMMY_SSID",
        "useDhcp": false,
        "ip": "999.999.999.999", 
        "gateway": "192.168.1.1",
        "subnet": "255.255.255.0",
        "connectTimeout": 1000
    })";
    create_wifi_config_file("/test_wifi.json", json);

    bool result = connectToWiFi("/test_wifi.json");
    TEST_ASSERT_FALSE(result);
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------

void setup() {
    Serial.begin(115200);
    delay(2000); 
    
    UNITY_BEGIN();
    
    RUN_TEST(test_wifi_config_missing_file);
    RUN_TEST(test_wifi_config_empty_file);
    RUN_TEST(test_wifi_config_invalid_json);
    RUN_TEST(test_wifi_config_missing_ssid);
    RUN_TEST(test_wifi_connect_timeout);
    RUN_TEST(test_wifi_static_ip_parsing);
    RUN_TEST(test_wifi_static_ip_invalid_format);
    
    UNITY_END();
}

void loop() {
}
