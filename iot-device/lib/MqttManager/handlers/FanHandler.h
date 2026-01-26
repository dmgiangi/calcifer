//
// FanHandler.h - Handler for AC dimmer fan control (relay + TRIAC dimmer)
//

#pragma once

#include "IDeviceHandler.h"
#include "rbdimmerESP32.h"
#include <map>
#include <set>

/**
 * @brief Handler for FAN mode (AC dimmer control).
 *
 * Coordinates a relay (on/off) with a TRIAC AC dimmer for fan speed control.
 * - Value 0: Relay OFF, Dimmer 0%
 * - Value 1-100: Relay ON, Dimmer mapped from minPwm to 100%
 *
 * Subscribes to MQTT command topic and publishes state to state topic.
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
     * @brief Maps MQTT value (0-100) to dimmer level (0-100) with minPwm threshold.
     *
     * Uses linear interpolation with proper rounding to map the MQTT percentage
     * input to the effective dimmer range (minPwm to 100%).
     *
     * @param mqttValue Value from MQTT (0-100 percentage)
     * @param minPwm Minimum PWM threshold (0-100)
     * @return Dimmer level: 0 if mqttValue <= 0, otherwise minPwm to 100
     */
    static uint8_t mapToDimmerLevel(int mqttValue, int minPwm);

private:
    // Static flag to track if rbdimmer library has been initialized
    static bool rbdimmerInitialized;
    
    // Static set to track which zero-cross pins are already registered
    static std::set<int> registeredZeroCrossPins;
    
    // Static map to store current state per pin (for state publishing)
    static std::map<int, String> currentState;
    
    // Static map to store dimmer channel per relay pin
    static std::map<int, rbdimmer_channel_t*> dimmerChannels;
    
    /**
     * @brief Ensures rbdimmer library is initialized.
     * @return true if initialization succeeded or was already done.
     */
    static bool ensureRbdimmerInit();
    
    /**
     * @brief Registers a zero-cross pin if not already registered.
     * @param pin Zero-cross GPIO pin
     * @param phase Phase number (0 for single phase)
     * @return true if registration succeeded or was already done.
     */
    static bool registerZeroCrossIfNeeded(int pin, uint8_t phase);

    /**
     * @brief Parses curve type string to rbdimmer curve enum.
     * @param curveType Curve type string ("LINEAR", "RMS", "LOGARITHMIC")
     * @return rbdimmer_curve_t enum value
     */
    static int parseCurveType(const String& curveType);
};

