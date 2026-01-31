//
// MqttDataProvider.h - Data provider bridging MqttManager to DisplayManager
//

#pragma once

#include "../IDisplayDataProvider.h"
#include <PinConfig.h>
#include <vector>

/**
 * @brief IDisplayDataProvider implementation that bridges to MqttManager.
 * 
 * Retrieves sensor values from producer read functions and actuator states
 * from handler static getState() methods. Also monitors WiFi and MQTT
 * connection status.
 * 
 * This class maintains loose coupling by:
 * - Using the IDisplayDataProvider abstraction
 * - Querying MqttManager through its public API
 * - Not modifying any MqttManager internals
 */
class MqttDataProvider : public IDisplayDataProvider {
public:
    /**
     * @brief Constructs the provider with pin configurations.
     * 
     * @param pinConfigs Reference to the loaded pin configurations
     *                   (used to know which devices exist and their types)
     */
    explicit MqttDataProvider(const std::vector<PinConfig>& pinConfigs);
    
    ~MqttDataProvider() override = default;

    // IDisplayDataProvider interface
    std::vector<DisplayItem> getDisplayableItems() override;
    ConnectionStatus getConnectionStatus() override;
    void refresh() override;

private:
    const std::vector<PinConfig>& pinConfigs_;
    std::vector<DisplayItem> cachedItems_;
    unsigned long lastRefresh_;
    
    // Helper methods to build display items for each device type
    void addSensorItem(const PinConfig& cfg);
    void addActuatorItem(const PinConfig& cfg);
    
    // Get unit string for device type
    static String getUnitForMode(PinModeType mode);
    
    // Get device type string for display
    static String getDeviceTypeString(PinModeType mode);
    
    // Check if mode is an actuator
    static bool isActuatorMode(PinModeType mode);
};

