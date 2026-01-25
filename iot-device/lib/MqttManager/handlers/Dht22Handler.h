//
// Dht22Handler.h - Handler for DHT22 temperature/humidity sensors
//

#pragma once

#include "IDeviceHandler.h"
#include <map>
#include <memory>

class DHT; // Forward declaration

/**
 * @brief Handler for DHT22_SENSOR mode.
 * Reads temperature and humidity, publishes to separate MQTT topics.
 */
class Dht22Handler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return DHT22_SENSOR; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;

    /**
     * @brief Gets the DHT sensor instance for a given pin.
     * Used by producer lambdas to read sensor data.
     */
    static DHT* getSensor(int pin);

private:
    // Static map to store DHT sensor instances
    static std::map<int, std::unique_ptr<DHT>> sensors;
};

