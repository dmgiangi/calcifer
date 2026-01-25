//
// DeviceHandlerRegistry.h - Registry for device handlers
//

#pragma once

#include "IDeviceHandler.h"
#include <map>
#include <memory>

/**
 * @brief Registry that manages all device handlers.
 * 
 * Implements the Factory/Registry pattern to decouple MqttManager
 * from specific device implementations.
 * 
 * Usage:
 *   DeviceHandlerRegistry::registerHandler(std::make_unique<DigitalInputHandler>());
 *   DeviceHandlerRegistry::initDevice(cfg, producers, consumers, clientId);
 */
class DeviceHandlerRegistry {
public:
    /**
     * @brief Registers a handler for a specific device type.
     * The handler's getHandledMode() determines which PinModeType it handles.
     */
    static void registerHandler(std::unique_ptr<IDeviceHandler> handler);

    /**
     * @brief Initializes a device using the appropriate registered handler.
     * 
     * @param cfg Pin configuration
     * @param producers Vector to add producers to
     * @param consumers Vector to add consumers to
     * @param clientId MQTT client ID for topic construction
     * @return true if a handler was found and init succeeded
     */
    static bool initDevice(const PinConfig& cfg,
                           std::vector<MqttProducer>& producers,
                           std::vector<MqttConsumer>& consumers,
                           const String& clientId);

    /**
     * @brief Registers all default handlers.
     * Call this once during setup to register all built-in handlers.
     */
    static void registerDefaultHandlers();

    /**
     * @brief Gets the PWM channel counter pointer for PwmHandler.
     */
    static int* getPwmChannelCounter() { return &pwmChannelCounter; }

    /**
     * @brief Clears all registered handlers (useful for testing).
     */
    static void clear();

private:
    static std::map<PinModeType, std::unique_ptr<IDeviceHandler>> handlers;
    static int pwmChannelCounter;
};

