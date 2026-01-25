//
// AnalogInputHandler.h - Handler for analog input pins (ADC)
//

#pragma once

#include "IDeviceHandler.h"

/**
 * @brief Handler for INPUT_ANALOG mode.
 * Reads ADC value and publishes to MQTT.
 */
class AnalogInputHandler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return INPUT_ANALOG; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;
};

