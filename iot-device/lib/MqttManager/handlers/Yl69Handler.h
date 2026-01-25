//
// Yl69Handler.h - Handler for YL-69 soil moisture sensors
//

#pragma once

#include "IDeviceHandler.h"

/**
 * @brief Handler for YL_69_SENSOR mode.
 * Reads soil moisture and publishes percentage to MQTT.
 */
class Yl69Handler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return YL_69_SENSOR; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;
};

