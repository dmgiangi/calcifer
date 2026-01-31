//
// test_native_display.cpp - Native PC unit tests for DisplayManager components
//
// These tests run on the development machine without ESP32 hardware.
// They test the core logic without hardware dependencies.
//

#include <unity.h>
#include <string>
#include <vector>

// ============================================================================
// Mock Arduino String class for native testing
// ============================================================================
#ifdef NATIVE_TEST

class String {
public:
    String() : data_("") {}
    String(const char* s) : data_(s ? s : "") {}
    String(const std::string& s) : data_(s) {}
    String(int val) : data_(std::to_string(val)) {}
    String(float val, int decimals = 2) {
        char buf[32];
        snprintf(buf, sizeof(buf), "%.*f", decimals, val);
        data_ = buf;
    }
    
    const char* c_str() const { return data_.c_str(); }
    size_t length() const { return data_.length(); }
    bool isEmpty() const { return data_.empty(); }
    
    String operator+(const String& other) const {
        return String(data_ + other.data_);
    }
    
    String operator+(const char* other) const {
        return String(data_ + (other ? other : ""));
    }
    
    bool operator==(const String& other) const {
        return data_ == other.data_;
    }
    
    bool operator==(const char* other) const {
        return data_ == (other ? other : "");
    }
    
    String substring(size_t start, size_t end = std::string::npos) const {
        if (start >= data_.length()) return String();
        return String(data_.substr(start, end - start));
    }
    
private:
    std::string data_;
};

#endif // NATIVE_TEST

// ============================================================================
// DisplayItem struct (copy from IDisplayDataProvider.h for native testing)
// ============================================================================

struct DisplayItem {
    String deviceName;
    String deviceType;
    String value;
    String unit;
    bool isActuator;
    String commandedValue;
    
    DisplayItem(const String& name, const String& type, const String& val,
                const String& u = "", bool actuator = false, 
                const String& commanded = "")
        : deviceName(name), deviceType(type), value(val), unit(u),
          isActuator(actuator), commandedValue(commanded) {}
};

struct ConnectionStatus {
    bool wifiConnected = true;
    bool mqttConnected = true;
    String errorMessage;
    
    bool hasError() const { return !wifiConnected || !mqttConnected; }
};

// ============================================================================
// Test: DisplayItem struct
// ============================================================================

void test_display_item_creation() {
    DisplayItem item("Temp1", "DS18", "25.5", "C", false);
    
    TEST_ASSERT_EQUAL_STRING("Temp1", item.deviceName.c_str());
    TEST_ASSERT_EQUAL_STRING("DS18", item.deviceType.c_str());
    TEST_ASSERT_EQUAL_STRING("25.5", item.value.c_str());
    TEST_ASSERT_EQUAL_STRING("C", item.unit.c_str());
    TEST_ASSERT_FALSE(item.isActuator);
}

void test_display_item_actuator() {
    DisplayItem item("Relay1", "DO", "ON", "", true, "ON");
    
    TEST_ASSERT_TRUE(item.isActuator);
    TEST_ASSERT_EQUAL_STRING("ON", item.commandedValue.c_str());
}

// ============================================================================
// Test: ConnectionStatus struct
// ============================================================================

void test_connection_status_no_error() {
    ConnectionStatus status;
    status.wifiConnected = true;
    status.mqttConnected = true;
    
    TEST_ASSERT_FALSE(status.hasError());
}

void test_connection_status_wifi_error() {
    ConnectionStatus status;
    status.wifiConnected = false;
    status.mqttConnected = true;
    status.errorMessage = "WiFi Disconnected";
    
    TEST_ASSERT_TRUE(status.hasError());
    TEST_ASSERT_EQUAL_STRING("WiFi Disconnected", status.errorMessage.c_str());
}

void test_connection_status_mqtt_error() {
    ConnectionStatus status;
    status.wifiConnected = true;
    status.mqttConnected = false;
    status.errorMessage = "MQTT Disconnected";
    
    TEST_ASSERT_TRUE(status.hasError());
}

// ============================================================================
// Test: Display rotation logic
// ============================================================================

class RotationLogic {
public:
    size_t currentIndex = 0;
    
    void rotateToNext(size_t itemCount) {
        if (itemCount == 0) {
            currentIndex = 0;
            return;
        }
        currentIndex = (currentIndex + 1) % itemCount;
    }
};

void test_rotation_basic() {
    RotationLogic logic;
    
    logic.rotateToNext(5);
    TEST_ASSERT_EQUAL(1, logic.currentIndex);
    
    logic.rotateToNext(5);
    TEST_ASSERT_EQUAL(2, logic.currentIndex);
}

void test_rotation_wraps_around() {
    RotationLogic logic;
    logic.currentIndex = 4;  // Last item (0-indexed)
    
    logic.rotateToNext(5);
    TEST_ASSERT_EQUAL(0, logic.currentIndex);  // Should wrap to 0
}

void test_rotation_empty_list() {
    RotationLogic logic;
    logic.currentIndex = 3;
    
    logic.rotateToNext(0);  // Empty list
    TEST_ASSERT_EQUAL(0, logic.currentIndex);  // Should reset to 0
}

void test_rotation_single_item() {
    RotationLogic logic;
    
    logic.rotateToNext(1);
    TEST_ASSERT_EQUAL(0, logic.currentIndex);  // Should stay at 0
    
    logic.rotateToNext(1);
    TEST_ASSERT_EQUAL(0, logic.currentIndex);  // Still 0
}

// ============================================================================
// Test: String formatting for display
// ============================================================================

void test_string_concat() {
    String name = "Temp";
    String value = "25.5";
    String unit = "C";
    
    String result = name + ": " + value + " " + unit;
    TEST_ASSERT_EQUAL_STRING("Temp: 25.5 C", result.c_str());
}

void test_float_to_string() {
    String temp(25.567f, 1);  // 1 decimal place
    TEST_ASSERT_EQUAL_STRING("25.6", temp.c_str());
}

// ============================================================================
// Main
// ============================================================================

int main(int argc, char **argv) {
    UNITY_BEGIN();
    
    // DisplayItem tests
    RUN_TEST(test_display_item_creation);
    RUN_TEST(test_display_item_actuator);
    
    // ConnectionStatus tests
    RUN_TEST(test_connection_status_no_error);
    RUN_TEST(test_connection_status_wifi_error);
    RUN_TEST(test_connection_status_mqtt_error);
    
    // Rotation logic tests
    RUN_TEST(test_rotation_basic);
    RUN_TEST(test_rotation_wraps_around);
    RUN_TEST(test_rotation_empty_list);
    RUN_TEST(test_rotation_single_item);
    
    // String formatting tests
    RUN_TEST(test_string_concat);
    RUN_TEST(test_float_to_string);
    
    return UNITY_END();
}

