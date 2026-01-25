//
// DigitalInputHandler.h - Handler for digital input pins
//

#pragma once

#include "IDeviceHandler.h"

/**
 * @brief Handler for INPUT_DIGITAL mode.
 * Reads digital pin state and publishes to MQTT.
 */
class DigitalInputHandler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return INPUT_DIGITAL; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;
};

