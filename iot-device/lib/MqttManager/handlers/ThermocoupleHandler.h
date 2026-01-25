//
// ThermocoupleHandler.h - Handler for MAX6675 thermocouple sensors
//

#pragma once

#include "IDeviceHandler.h"
#include <map>
#include <memory>
#include <max6675.h>

/**
 * @brief Handler for THERMOCOUPLE mode.
 * Reads temperature from MAX6675 thermocouple and publishes to MQTT.
 */
class ThermocoupleHandler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return THERMOCOUPLE; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;

    /**
     * @brief Gets the MAX6675 sensor instance for a given pin.
     * Used by producer lambdas to read sensor data.
     */
    static MAX6675* getSensor(int pin);

private:
    // Static map to store thermocouple sensor instances
    static std::map<int, std::unique_ptr<MAX6675>> sensors;
};

