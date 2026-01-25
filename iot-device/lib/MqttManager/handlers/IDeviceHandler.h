//
// IDeviceHandler.h - Interface for device handlers
// Refactored for SOLID principles
//

#pragma once

#include <Arduino.h>
#include <vector>
#include <functional>
#include <PinConfig.h>

/**
 * @brief Represents an entity that consumes MQTT messages to control a pin/device.
 */
struct MqttConsumer {
    int pin;
    String topic;
    std::function<void(int, const String &)> onMessage;

    String lastValue;
    String fallbackValue;
    unsigned long lastUpdate = 0;
    unsigned long interval = 0;
};

/**
 * @brief Represents an entity that produces MQTT messages by reading a pin/device.
 */
struct MqttProducer {
    int pin;
    String topic;
    unsigned long interval = 0;
    unsigned long lastPublish = 0;
    std::function<String(int)> readFn;

    MqttProducer(int p, String t, unsigned long i, unsigned long lp, std::function<String(int)> fn)
        : pin(p), topic(std::move(t)), interval(i), lastPublish(lp), readFn(std::move(fn)) {}
};

/**
 * @brief Interface for device handlers following Strategy Pattern.
 * 
 * Each handler is responsible for:
 * - Initializing hardware for a specific device type
 * - Creating appropriate MqttProducer or MqttConsumer entries
 * - Managing any device-specific state
 */
class IDeviceHandler {
public:
    virtual ~IDeviceHandler() = default;

    /**
     * @brief Returns the PinModeType this handler supports.
     */
    virtual PinModeType getHandledMode() const = 0;

    /**
     * @brief Initializes the device and registers producers/consumers.
     * 
     * @param cfg Pin configuration from JSON
     * @param producers Vector to add producers to
     * @param consumers Vector to add consumers to
     * @param clientId MQTT client ID for topic construction
     */
    virtual void init(const PinConfig& cfg,
                      std::vector<MqttProducer>& producers,
                      std::vector<MqttConsumer>& consumers,
                      const String& clientId) = 0;
};

