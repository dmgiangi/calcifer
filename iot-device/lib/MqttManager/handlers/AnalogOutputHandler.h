//
// AnalogOutputHandler.h - Handler for analog output pins (DAC)
//

#pragma once

#include "IDeviceHandler.h"
#include <map>

/**
 * @brief Handler for OUTPUT_ANALOG mode.
 * Subscribes to MQTT command topic and publishes state to state topic.
 */
class AnalogOutputHandler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return OUTPUT_ANALOG; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;

    // Static methods for state management (used by producer lambda)
    static String getState(int pin);
    static void setState(int pin, const String& value);

private:
    static std::map<int, String> currentState;
};

