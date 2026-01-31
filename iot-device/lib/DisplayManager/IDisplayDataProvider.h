//
// IDisplayDataProvider.h - Abstract interface for display data sources
// Follows Dependency Inversion Principle: DisplayManager depends on this abstraction
//

#pragma once

#include <Arduino.h>
#include <vector>

/**
 * @brief Represents a single item to be displayed.
 * 
 * Contains all information needed to render a sensor reading
 * or actuator state on the display.
 */
struct DisplayItem {
    String deviceName;      // Human-readable device name (e.g., "room-sensor")
    String deviceType;      // Device category (e.g., "DS18B20", "FAN", "PWM")
    String value;           // Current value as string (e.g., "23.5", "ON", "75")
    String unit;            // Unit of measurement (e.g., "°C", "%", "")
    bool isActuator;        // true for actuators (have commanded + actual state)
    String commandedValue;  // For actuators: the last commanded value from /set topic
    
    DisplayItem() : isActuator(false) {}
    
    DisplayItem(const String& name, const String& type, const String& val, 
                const String& u = "", bool actuator = false, const String& cmd = "")
        : deviceName(name), deviceType(type), value(val), unit(u), 
          isActuator(actuator), commandedValue(cmd) {}
};

/**
 * @brief Connection status information.
 */
struct ConnectionStatus {
    bool wifiConnected;
    bool mqttConnected;
    String errorMessage;
    
    ConnectionStatus() : wifiConnected(false), mqttConnected(false) {}
    
    bool hasError() const { return !wifiConnected || !mqttConnected; }
};

/**
 * @brief Abstract interface for providing data to the display.
 * 
 * Implementations bridge the display system to data sources
 * (e.g., MqttManager, direct sensor readings, mock data for testing).
 * 
 * This abstraction allows:
 * - Testing display logic without real hardware/network
 * - Future alternative data sources
 * - Clean separation between display and data layers
 */
class IDisplayDataProvider {
public:
    virtual ~IDisplayDataProvider() = default;

    /**
     * @brief Gets all items that should be displayed.
     * 
     * Returns a snapshot of current sensor readings and actuator states.
     * The display will rotate through these items.
     * 
     * @return Vector of DisplayItem structs
     */
    virtual std::vector<DisplayItem> getDisplayableItems() = 0;

    /**
     * @brief Gets the current connection status.
     * 
     * Used to determine if error mode should be displayed.
     * 
     * @return ConnectionStatus with WiFi and MQTT state
     */
    virtual ConnectionStatus getConnectionStatus() = 0;

    /**
     * @brief Refreshes cached data from underlying sources.
     * 
     * Called periodically to update internal state.
     * Implementations should be non-blocking.
     */
    virtual void refresh() = 0;
};

