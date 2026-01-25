#include <Arduino.h>
#include <unity.h>
#include <PinConfig.h>
#include <SPIFFS.h>

// Helper to write a clean config file to SPIFFS
void create_config_file(const char* filename, const char* content) {
    if (SPIFFS.exists(filename)) {
        SPIFFS.remove(filename);
    }
    File f = SPIFFS.open(filename, "w");
    if (f) {
        size_t written = f.print(content);
        f.flush();
        f.close();
        
        if (written != strlen(content)) {
            char msg[64];
            snprintf(msg, sizeof(msg), "Failed to write full content. Expected %d, wrote %d", strlen(content), written);
            TEST_FAIL_MESSAGE(msg);
        }
    } else {
        TEST_FAIL_MESSAGE("Failed to open file for writing in SPIFFS");
    }
}

// Helper to verify file exists and has content
void verify_file_exists(const char* filename) {
    if (!SPIFFS.exists(filename)) {
        char msg[64];
        snprintf(msg, sizeof(msg), "File %s does not exist after creation", filename);
        TEST_FAIL_MESSAGE(msg);
    }
    File f = SPIFFS.open(filename, "r");
    if (!f) {
        TEST_FAIL_MESSAGE("Failed to open file for reading check");
    }
    if (f.size() == 0) {
        TEST_FAIL_MESSAGE("File is empty after creation");
    }
    
    // Debug: Print file content to Serial to verify what was written
    Serial.printf("DEBUG: Content of %s:\n", filename);
    while(f.available()) {
        Serial.write(f.read());
    }
    Serial.println("\nDEBUG: End of file content");
    
    f.close();
}

// ----------------------------------------------------------------------------
// Setup and Teardown
// ----------------------------------------------------------------------------

void setUp(void) {
    // Ensure SPIFFS is ready
    if (!SPIFFS.begin(true)) {
        TEST_FAIL_MESSAGE("SPIFFS Mount Failed");
    }
}

void tearDown(void) {
    // Clean up test files if they exist
    if (SPIFFS.exists("/test_valid.json")) SPIFFS.remove("/test_valid.json");
    if (SPIFFS.exists("/test_mixed.json")) SPIFFS.remove("/test_mixed.json");
    if (SPIFFS.exists("/test_thermo.json")) SPIFFS.remove("/test_thermo.json");
    if (SPIFFS.exists("/test_malformed.json")) SPIFFS.remove("/test_malformed.json");
    if (SPIFFS.exists("/test_object.json")) SPIFFS.remove("/test_object.json");
}

// ----------------------------------------------------------------------------
// Test Cases: parseMode
// ----------------------------------------------------------------------------

void test_parse_mode_valid() {
    TEST_ASSERT_EQUAL(INPUT_DIGITAL, parseMode("INPUT_DIGITAL"));
    TEST_ASSERT_EQUAL(OUTPUT_DIGITAL, parseMode("OUTPUT_DIGITAL"));
    TEST_ASSERT_EQUAL(PWM, parseMode("PWM"));
    TEST_ASSERT_EQUAL(INPUT_ANALOG, parseMode("INPUT_ANALOG"));
    TEST_ASSERT_EQUAL(OUTPUT_ANALOG, parseMode("OUTPUT_ANALOG"));
    TEST_ASSERT_EQUAL(DHT22_SENSOR, parseMode("DHT22_SENSOR"));
    TEST_ASSERT_EQUAL(YL_69_SENSOR, parseMode("YL_69_SENSOR"));
    TEST_ASSERT_EQUAL(DS18B20, parseMode("DS18B20"));
    TEST_ASSERT_EQUAL(THERMOCOUPLE, parseMode("THERMOCOUPLE"));
}

void test_parse_mode_case_insensitive() {
    TEST_ASSERT_EQUAL(INPUT_DIGITAL, parseMode("input_digital"));
    TEST_ASSERT_EQUAL(PWM, parseMode("pwm"));
    TEST_ASSERT_EQUAL(OUTPUT_ANALOG, parseMode("Output_Analog"));
}

void test_parse_mode_invalid() {
    TEST_ASSERT_EQUAL(INVALID, parseMode("NOT_A_MODE"));
    TEST_ASSERT_EQUAL(INVALID, parseMode(""));
    TEST_ASSERT_EQUAL(INVALID, parseMode("123"));
}

// ----------------------------------------------------------------------------
// Test Cases: isValidConfig (Refactored)
// ----------------------------------------------------------------------------

void test_is_valid_config_allowed() {
    PinConfig cfg;
    
    // GPIO13 supports INPUT_DIGITAL, OUTPUT_DIGITAL, PWM
    cfg.pin = 13;
    cfg.mode = INPUT_DIGITAL;
    TEST_ASSERT_TRUE(isValidConfig(cfg));
    
    cfg.mode = OUTPUT_DIGITAL;
    TEST_ASSERT_TRUE(isValidConfig(cfg));
    
    cfg.mode = PWM;
    TEST_ASSERT_TRUE(isValidConfig(cfg));
    
    // GPIO34 is input only
    cfg.pin = 34;
    cfg.mode = INPUT_DIGITAL;
    TEST_ASSERT_TRUE(isValidConfig(cfg));
    
    cfg.mode = INPUT_ANALOG;
    TEST_ASSERT_TRUE(isValidConfig(cfg));
    
    // GPIO25 supports DAC (OUTPUT_ANALOG)
    cfg.pin = 25;
    cfg.mode = OUTPUT_ANALOG;
    TEST_ASSERT_TRUE(isValidConfig(cfg));
}

void test_is_valid_config_forbidden() {
    PinConfig cfg;

    // GPIO13 does NOT support Analog Input
    cfg.pin = 13;
    cfg.mode = INPUT_ANALOG;
    TEST_ASSERT_FALSE(isValidConfig(cfg));
    
    // GPIO34 is input only, no Output/PWM
    cfg.pin = 34;
    cfg.mode = OUTPUT_DIGITAL;
    TEST_ASSERT_FALSE(isValidConfig(cfg));
    
    cfg.mode = PWM;
    TEST_ASSERT_FALSE(isValidConfig(cfg));
    
    cfg.mode = OUTPUT_ANALOG;
    TEST_ASSERT_FALSE(isValidConfig(cfg));
}

void test_is_valid_config_thermocouple() {
    PinConfig cfg;
    cfg.mode = THERMOCOUPLE;
    
    // Valid Config: CS=22(Out), SCK=18(Out), SO=19(In)
    cfg.pin = 22;      // CS
    cfg.pinClock = 18; // SCK
    cfg.pinData = 19;  // SO
    TEST_ASSERT_TRUE(isValidConfig(cfg));
    
    // Invalid: SO on Output-only pin (unlikely on ESP32, but let's try non-input pin if any, or invalid pin)
    // Actually, let's try putting SCK on an input-only pin (34)
    cfg.pinClock = 34; 
    TEST_ASSERT_FALSE(isValidConfig(cfg)); // SCK must be output capable
}

void test_is_valid_config_invalid_pin() {
    PinConfig cfg;
    cfg.pin = 99;
    cfg.mode = INPUT_DIGITAL;
    TEST_ASSERT_FALSE(isValidConfig(cfg));
}

// ----------------------------------------------------------------------------
// Test Cases: loadConfiguration
// ----------------------------------------------------------------------------

void test_load_valid_config() {
    const char* filename = "/test_valid.json";
    const char* json = R"([ 
        {
            "pin": 13,
            "mode": "OUTPUT_DIGITAL",
            "name": "Test LED",
            "defaultState": 1
        },
        {
            "pin": 34,
            "mode": "INPUT_ANALOG",
            "name": "Test Pot",
            "pollingInterval": 500
        }
    ])";

    create_config_file(filename, json);
    verify_file_exists(filename);

    std::vector<PinConfig> configs = loadConfiguration(filename);

    TEST_ASSERT_EQUAL(2, configs.size());

    if (configs.size() >= 1) {
        TEST_ASSERT_EQUAL(13, configs[0].pin);
        TEST_ASSERT_EQUAL(OUTPUT_DIGITAL, configs[0].mode);
        TEST_ASSERT_EQUAL_STRING("Test LED", configs[0].name.c_str());
        TEST_ASSERT_EQUAL(1, configs[0].defaultState);
    }

    if (configs.size() >= 2) {
        TEST_ASSERT_EQUAL(34, configs[1].pin);
        TEST_ASSERT_EQUAL(INPUT_ANALOG, configs[1].mode);
        TEST_ASSERT_EQUAL_STRING("Test Pot", configs[1].name.c_str());
        TEST_ASSERT_EQUAL(500, configs[1].pollingInterval);
    }
}

void test_load_thermocouple_config() {
    const char* filename = "/test_thermo.json";
    const char* json = R"([ 
        {
            "name": "Furnace",
            "mode": "THERMOCOUPLE",
            "pin": 22,
            "sck": 18,
            "so": 19,
            "pollingInterval": 1000
        }
    ])";
    
    create_config_file(filename, json);
    verify_file_exists(filename);
    
    std::vector<PinConfig> configs = loadConfiguration(filename);
    
    TEST_ASSERT_EQUAL(1, configs.size());
    if (configs.size() > 0) {
        TEST_ASSERT_EQUAL(22, configs[0].pin);
        TEST_ASSERT_EQUAL(18, configs[0].pinClock);
        TEST_ASSERT_EQUAL(19, configs[0].pinData);
        TEST_ASSERT_EQUAL(THERMOCOUPLE, configs[0].mode);
    }
}

void test_load_config_with_invalid_entries() {
    const char* filename = "/test_mixed.json";
    // First entry valid
    // Second invalid mode
    // Third invalid capability
    // Fourth incomplete thermocouple (missing SCK)
    const char* json = R"([ 
        { "pin": 13, "mode": "OUTPUT_DIGITAL", "name": "Valid" },
        { "pin": 14, "mode": "SUPER_LASER", "name": "Invalid Mode" },
        { "pin": 34, "mode": "OUTPUT_DIGITAL", "name": "Invalid Capability" },
        { "pin": 22, "mode": "THERMOCOUPLE", "name": "Broken Thermo" } 
    ])";

    create_config_file(filename, json);
    verify_file_exists(filename);

    std::vector<PinConfig> configs = loadConfiguration(filename);

    // Should only load the one valid entry
    TEST_ASSERT_EQUAL(1, configs.size());
    if (configs.size() > 0) {
        TEST_ASSERT_EQUAL(13, configs[0].pin);
    }
}

void test_load_config_malformed_json() {
    const char* filename = "/test_malformed.json";
    const char* json = "{ 'this' : is not json }"; // Broken JSON

    create_config_file(filename, json);
    
    std::vector<PinConfig> configs = loadConfiguration(filename);

    TEST_ASSERT_TRUE(configs.empty());
}

void test_load_config_not_array() {
    const char* filename = "/test_object.json";
    const char* json = "{ \"pin\": 13 }"; // Valid JSON but object, not array

    create_config_file(filename, json);

    std::vector<PinConfig> configs = loadConfiguration(filename);

    TEST_ASSERT_TRUE(configs.empty());
}

void test_load_missing_file() {
    std::vector<PinConfig> configs = loadConfiguration("/does_not_exist.json");
    TEST_ASSERT_TRUE(configs.empty());
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------

void setup() {
    Serial.begin(115200);
    delay(2000); // Wait for Serial to stabilize
    
    UNITY_BEGIN();
    
    RUN_TEST(test_parse_mode_valid);
    RUN_TEST(test_parse_mode_case_insensitive);
    RUN_TEST(test_parse_mode_invalid);
    
    RUN_TEST(test_is_valid_config_allowed);
    RUN_TEST(test_is_valid_config_forbidden);
    RUN_TEST(test_is_valid_config_thermocouple);
    RUN_TEST(test_is_valid_config_invalid_pin);
    
    RUN_TEST(test_load_valid_config);
    RUN_TEST(test_load_thermocouple_config);
    RUN_TEST(test_load_config_with_invalid_entries);
    RUN_TEST(test_load_config_malformed_json);
    RUN_TEST(test_load_config_not_array);
    RUN_TEST(test_load_missing_file);
    
    UNITY_END();
}

void loop() {
    // Empty
}
