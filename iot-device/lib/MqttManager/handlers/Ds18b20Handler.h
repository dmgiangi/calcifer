//
// Ds18b20Handler.h - Handler for DS18B20 temperature sensors
//

#pragma once

#include "IDeviceHandler.h"
#include <map>
#include <memory>
#include <OneWire.h>
#include <DallasTemperature.h>

/**
 * @brief Handler for DS18B20 mode.
 * Reads temperature from OneWire DS18B20 sensor and publishes to MQTT.
 */
class Ds18b20Handler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return DS18B20; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;

    /**
     * @brief Gets the DallasTemperature sensor instance for a given pin.
     * Used by producer lambdas to read sensor data.
     */
    static DallasTemperature* getSensor(int pin);

private:
    // Static maps to store sensor instances (must keep OneWire alive)
    static std::map<int, std::unique_ptr<OneWire>> oneWireInstances;
    static std::map<int, std::unique_ptr<DallasTemperature>> sensors;
};

