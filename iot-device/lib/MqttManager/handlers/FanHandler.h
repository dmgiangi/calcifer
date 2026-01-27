//
// FanHandler.h - Handler for 3-relay fan control with 5 discrete speed states
//

#pragma once

#include "IDeviceHandler.h"
#include <map>

/**
 * @brief Handler for FAN mode (3-relay discrete speed control).
 *
 * Controls fan speed using 3 relays that provide 5 discrete speed states:
 * - State 0: All relays OFF (fan stopped)
 * - State 1: Only relay 1 ON (lowest speed)
 * - State 2: Only relay 2 ON (medium-low speed)
 * - State 3: Relays 1 AND 2 ON (medium-high speed)
 * - State 4: Only relay 3 ON (highest speed)
 *
 * MQTT API accepts 0-100 percentage values for backward compatibility:
 * - 0 -> State 0, feedback "0"
 * - 1-25 -> State 1, feedback "25"
 * - 26-50 -> State 2, feedback "50"
 * - 51-75 -> State 3, feedback "75"
 * - 76-100 -> State 4, feedback "100"
 */
class FanHandler : public IDeviceHandler {
public:
    PinModeType getHandledMode() const override { return FAN; }

    void init(const PinConfig& cfg,
              std::vector<MqttProducer>& producers,
              std::vector<MqttConsumer>& consumers,
              const String& clientId) override;

    // Static methods for state management (used by producer lambda)
    static String getState(int pin);
    static void setState(int pin, const String& value);

    /**
     * @brief Converts MQTT value (0-100) to internal state (0-4).
     *
     * @param mqttValue Value from MQTT (0-100 percentage)
     * @return Internal state: 0-4
     */
    static uint8_t mqttToState(int mqttValue);

    /**
     * @brief Converts internal state (0-4) to MQTT feedback value.
     *
     * @param state Internal state (0-4)
     * @return MQTT value: 0, 25, 50, 75, or 100
     */
    static int stateToMqtt(uint8_t state);

private:
    // Static map to store current state per pin (for state publishing)
    static std::map<int, String> currentState;

    /**
     * @brief Applies relay states based on internal state (0-4).
     *
     * Turns all relays OFF first (safety), then turns ON the required ones.
     *
     * @param state Internal state (0-4)
     * @param relay1 GPIO pin for relay 1
     * @param relay2 GPIO pin for relay 2
     * @param relay3 GPIO pin for relay 3
     * @param inverted If true, relay logic is inverted (HIGH=OFF, LOW=ON)
     */
    static void applyRelayState(uint8_t state, int relay1, int relay2, int relay3, bool inverted);
};
