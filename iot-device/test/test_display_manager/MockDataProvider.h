//
// MockDataProvider.h - Mock IDisplayDataProvider implementation for testing
//

#pragma once

#include <IDisplayDataProvider.h>

/**
 * @brief Mock IDisplayDataProvider for unit testing.
 * 
 * Allows setting predetermined return values for testing.
 */
class MockDataProvider : public IDisplayDataProvider {
public:
    std::vector<DisplayItem> getDisplayableItems() override {
        return items;
    }

    ConnectionStatus getConnectionStatus() override {
        return status;
    }

    void refresh() override {
        refreshCallCount++;
    }

    // Test configuration
    void setItems(const std::vector<DisplayItem>& newItems) {
        items = newItems;
    }

    void addItem(const String& name, const String& type, const String& value, 
                 const String& unit = "", bool isActuator = false, 
                 const String& commanded = "") {
        items.emplace_back(name, type, value, unit, isActuator, commanded);
    }

    void setConnectionStatus(bool wifi, bool mqtt, const String& error = "") {
        status.wifiConnected = wifi;
        status.mqttConnected = mqtt;
        status.errorMessage = error;
    }

    void clearItems() {
        items.clear();
    }

    // Test state
    std::vector<DisplayItem> items;
    ConnectionStatus status;
    int refreshCallCount = 0;
};

