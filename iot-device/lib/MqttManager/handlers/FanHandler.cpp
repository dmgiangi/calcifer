//
// FanHandler.cpp - AC dimmer fan control (relay + TRIAC dimmer)
//

#include "FanHandler.h"
#include "rbdimmerESP32.h"
#include <Logger.h>

static const char* TAG = "FAN";

// MQTT value range constant (0-255, so max-min = 254 for interpolation)
namespace {
    constexpr int MQTT_VALUE_MAX = 255;
    constexpr int MQTT_VALUE_RANGE = MQTT_VALUE_MAX - 1;  // 254 for linear interpolation
}

// Static member initialization
bool FanHandler::rbdimmerInitialized = false;
std::set<int> FanHandler::registeredZeroCrossPins;
std::map<int, String> FanHandler::currentState;
std::map<int, rbdimmer_channel_t*> FanHandler::dimmerChannels;

// ============================================================================
// Static Helper Methods
// ============================================================================

String FanHandler::getState(int pin) {
    auto it = currentState.find(pin);
    return (it != currentState.end()) ? it->second : String("0");
}

void FanHandler::setState(int pin, const String& value) {
    currentState[pin] = value;
}

bool FanHandler::ensureRbdimmerInit() {
    if (rbdimmerInitialized) {
        return true;
    }
    
    if (rbdimmer_init() != RBDIMMER_OK) {
        LOG_ERROR(TAG, "Failed to initialize rbdimmer library");
        return false;
    }
    
    rbdimmerInitialized = true;
    LOG_INFO(TAG, "rbdimmer library initialized");
    return true;
}

bool FanHandler::registerZeroCrossIfNeeded(int pin, uint8_t phase) {
    if (registeredZeroCrossPins.count(pin) > 0) {
        LOG_DEBUG(TAG, "Zero-cross pin %d already registered", pin);
        return true;
    }
    
    if (rbdimmer_register_zero_cross(pin, phase, 0) != RBDIMMER_OK) {
        LOG_ERROR(TAG, "Failed to register zero-cross on GPIO%d", pin);
        return false;
    }
    
    registeredZeroCrossPins.insert(pin);
    LOG_INFO(TAG, "Zero-cross registered on GPIO%d for phase %d", pin, phase);
    return true;
}

uint8_t FanHandler::mapToDimmerLevel(int mqttValue, int minPwm) {
    if (mqttValue <= 0) {
        return 0;
    }
    // Map MQTT 1-255 to minPwm-100 range
    // Linear interpolation: output = minPwm + (mqttValue - 1) * (100 - minPwm) / MQTT_VALUE_RANGE
    int mapped = minPwm + ((mqttValue - 1) * (100 - minPwm)) / MQTT_VALUE_RANGE;
    return constrain(mapped, minPwm, 100);
}

int FanHandler::parseCurveType(const String& curveType) {
    String ct = curveType;
    ct.toUpperCase();
    
    if (ct == "LINEAR") {
        return RBDIMMER_CURVE_LINEAR;
    } else if (ct == "LOGARITHMIC" || ct == "LOG") {
        return RBDIMMER_CURVE_LOGARITHMIC;
    }
    // Default to RMS (best for motors/fans)
    return RBDIMMER_CURVE_RMS;
}

// ============================================================================
// Handler Implementation
// ============================================================================

void FanHandler::init(const PinConfig& cfg,
                      std::vector<MqttProducer>& producers,
                      std::vector<MqttConsumer>& consumers,
                      const String& clientId) {
    
    // 1. Initialize rbdimmer library (only once)
    if (!ensureRbdimmerInit()) {
        LOG_ERROR(TAG, "Skipping FAN %s - rbdimmer init failed", cfg.name.c_str());
        return;
    }
    
    // 2. Register zero-cross detector (only once per pin)
    if (!registerZeroCrossIfNeeded(cfg.pinZeroCross, 0)) {
        LOG_ERROR(TAG, "Skipping FAN %s - zero-cross registration failed", cfg.name.c_str());
        return;
    }
    
    // 3. Setup relay pin
    pinMode(cfg.pin, OUTPUT);
    bool relayOff = cfg.inverted ? HIGH : LOW;
    digitalWrite(cfg.pin, relayOff);
    
    // 4. Create dimmer channel
    rbdimmer_config_t dimmerConfig = {
        .gpio_pin = static_cast<uint8_t>(cfg.pinDimmer),
        .phase = 0,
        .initial_level = 0,
        .curve_type = static_cast<rbdimmer_curve_t>(parseCurveType(cfg.curveType))
    };
    
    rbdimmer_channel_t* channel = nullptr;
    if (rbdimmer_create_channel(&dimmerConfig, &channel) != RBDIMMER_OK) {
        LOG_ERROR(TAG, "Failed to create dimmer channel for %s", cfg.name.c_str());
        return;
    }
    
    // Store channel reference
    dimmerChannels[cfg.pin] = channel;
    
    // 5. Initialize state
    currentState[cfg.pin] = String(cfg.defaultState);
    
    // 6. Apply default state
    int defaultVal = cfg.defaultState;
    if (defaultVal > 0) {
        uint8_t level = mapToDimmerLevel(defaultVal, cfg.minPwm);
        rbdimmer_set_level(channel, level);
        digitalWrite(cfg.pin, cfg.inverted ? LOW : HIGH);
    }
    
    // 7. Setup MQTT topics
    String cmdTopic = "/" + clientId + "/fan/" + cfg.name + "/set";
    String stateTopic = "/" + clientId + "/fan/" + cfg.name + "/state";

    // 8. Create consumer for command topic
    // Capture needed values for lambda
    int relayPin = cfg.pin;
    int minPwm = cfg.minPwm;
    bool inverted = cfg.inverted;

    auto c = MqttConsumer::createForActuator(cfg, cmdTopic,
        [relayPin, minPwm, inverted, channel](int p, const String& msg) {
            int mqttValue = constrain(msg.toInt(), 0, 255);

            if (mqttValue == 0) {
                // Turn OFF: Relay OFF, Dimmer 0%
                rbdimmer_set_level(channel, 0);
                digitalWrite(relayPin, inverted ? HIGH : LOW);
                FanHandler::setState(relayPin, "0");
                LOG_DEBUG(TAG, "FAN GPIO%d OFF", relayPin);
            } else {
                // Turn ON: Relay ON, Dimmer mapped level
                uint8_t level = FanHandler::mapToDimmerLevel(mqttValue, minPwm);
                digitalWrite(relayPin, inverted ? LOW : HIGH);
                rbdimmer_set_level(channel, level);
                FanHandler::setState(relayPin, String(mqttValue));
                LOG_DEBUG(TAG, "FAN GPIO%d <- %d (dimmer: %d%%)", relayPin, mqttValue, level);
            }
        });

    consumers.push_back(std::move(c));

    // 9. Add producer for state publishing
    if (cfg.pollingInterval > 0) {
        producers.emplace_back(cfg.pin, stateTopic, cfg.pollingInterval, 0,
            [](int pin) {
                return FanHandler::getState(pin);
            });
    }

    LOG_INFO(TAG, "FAN %s initialized: relay=GPIO%d, dimmer=GPIO%d, zc=GPIO%d, minPwm=%d, curve=%s",
             cfg.name.c_str(), cfg.pin, cfg.pinDimmer, cfg.pinZeroCross,
             cfg.minPwm, cfg.curveType.c_str());
    LOG_INFO(TAG, "  -> cmd: %s, state: %s", cmdTopic.c_str(), stateTopic.c_str());
}

