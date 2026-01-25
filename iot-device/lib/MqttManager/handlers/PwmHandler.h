//
// PwmHandler.h - Handler for PWM output pins
//

#pragma once

#include "IDeviceHandler.h"
#include <map>

/**
 * @brief Handler for PWM mode.
 * Subscribes to MQTT command topic and publishes state to state topic.
 */
class PwmHandler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return PWM; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;

    /**
     * @brief Sets the shared PWM channel counter reference.
     * Must be called before init() to properly allocate channels.
     */
    void setChannelCounter(int* counter) { nextPwmChannel = counter; }

    // Static methods for state management (used by producer lambda)
    static String getState(int pin);
    static void setState(int pin, const String& value);

private:
    int* nextPwmChannel = nullptr;
    static std::map<int, String> currentState;
};

