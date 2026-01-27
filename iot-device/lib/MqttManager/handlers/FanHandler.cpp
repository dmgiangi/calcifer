//
// FanHandler.cpp - 3-relay fan control with 5 discrete speed states
//

#include "FanHandler.h"
#include <Arduino.h>
#include <Logger.h>

static const char* TAG = "FAN";

// Relay state lookup table for each speed state (0-4)
// Each row: {Relay1, Relay2, Relay3} where 1=ON, 0=OFF
static const uint8_t RELAY_STATES[5][3] = {
    {0, 0, 0},  // State 0: OFF
    {1, 0, 0},  // State 1: R1 only (lowest speed)
    {0, 1, 0},  // State 2: R2 only (medium-low speed)
    {1, 1, 0},  // State 3: R1 + R2 (medium-high speed)
    {0, 0, 1}   // State 4: R3 only (highest speed)
};

// MQTT feedback values for each state
static const int MQTT_FEEDBACK[5] = {0, 25, 50, 75, 100};

// Static member initialization
std::map<int, String> FanHandler::currentState;

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

uint8_t FanHandler::mqttToState(int mqttValue) {
    if (mqttValue <= 0) return 0;
    if (mqttValue <= 25) return 1;
    if (mqttValue <= 50) return 2;
    if (mqttValue <= 75) return 3;
    return 4;
}

int FanHandler::stateToMqtt(uint8_t state) {
    return (state <= 4) ? MQTT_FEEDBACK[state] : 0;
}

void FanHandler::applyRelayState(uint8_t state, int relay1, int relay2, int relay3, bool inverted) {
    // Safety: Turn all relays OFF first
    uint8_t offLevel = inverted ? HIGH : LOW;
    digitalWrite(relay1, offLevel);
    digitalWrite(relay2, offLevel);
    digitalWrite(relay3, offLevel);

    // Apply new state (if valid)
    if (state <= 4) {
        uint8_t onLevel = inverted ? LOW : HIGH;
        digitalWrite(relay1, RELAY_STATES[state][0] ? onLevel : offLevel);
        digitalWrite(relay2, RELAY_STATES[state][1] ? onLevel : offLevel);
        digitalWrite(relay3, RELAY_STATES[state][2] ? onLevel : offLevel);
    }
}

// ============================================================================
// Handler Implementation
// ============================================================================

void FanHandler::init(const PinConfig& cfg,
                      std::vector<MqttProducer>& producers,
                      std::vector<MqttConsumer>& consumers,
                      const String& clientId) {

    // 1. Setup all 3 relay pins as outputs
    pinMode(cfg.pin, OUTPUT);
    pinMode(cfg.pinRelay2, OUTPUT);
    pinMode(cfg.pinRelay3, OUTPUT);

    // 2. Initialize all relays to OFF
    uint8_t offLevel = cfg.inverted ? HIGH : LOW;
    digitalWrite(cfg.pin, offLevel);
    digitalWrite(cfg.pinRelay2, offLevel);
    digitalWrite(cfg.pinRelay3, offLevel);

    // 3. Apply default state
    uint8_t defaultState = mqttToState(cfg.defaultState);
    applyRelayState(defaultState, cfg.pin, cfg.pinRelay2, cfg.pinRelay3, cfg.inverted);

    // 4. Initialize state storage with MQTT feedback value
    currentState[cfg.pin] = String(stateToMqtt(defaultState));

    // 5. Setup MQTT topics
    String cmdTopic = "/" + clientId + "/fan/" + cfg.name + "/set";
    String stateTopic = "/" + clientId + "/fan/" + cfg.name + "/state";

    // 6. Capture values for lambda
    int r1 = cfg.pin;
    int r2 = cfg.pinRelay2;
    int r3 = cfg.pinRelay3;
    bool inverted = cfg.inverted;

    // 7. Create consumer for command topic
    auto c = MqttConsumer::createForActuator(cfg, cmdTopic,
        [r1, r2, r3, inverted](int p, const String& msg) {
            int mqttValue = constrain(msg.toInt(), 0, 100);
            uint8_t state = FanHandler::mqttToState(mqttValue);

            FanHandler::applyRelayState(state, r1, r2, r3, inverted);
            FanHandler::setState(r1, String(FanHandler::stateToMqtt(state)));

            LOG_DEBUG(TAG, "FAN [R1=%d,R2=%d,R3=%d] <- MQTT:%d -> State:%d -> R1:%s R2:%s R3:%s",
                      r1, r2, r3, mqttValue, state,
                      RELAY_STATES[state][0] ? "ON" : "OFF",
                      RELAY_STATES[state][1] ? "ON" : "OFF",
                      RELAY_STATES[state][2] ? "ON" : "OFF");
        });

    consumers.push_back(std::move(c));

    // 8. Add producer for state publishing
    if (cfg.pollingInterval > 0) {
        producers.emplace_back(cfg.pin, stateTopic, cfg.pollingInterval, 0,
            [](int pin) {
                return FanHandler::getState(pin);
            });
    }

    LOG_INFO(TAG, "FAN %s initialized: R1=GPIO%d, R2=GPIO%d, R3=GPIO%d, inverted=%s",
             cfg.name.c_str(), cfg.pin, cfg.pinRelay2, cfg.pinRelay3,
             cfg.inverted ? "true" : "false");
    LOG_INFO(TAG, "  -> cmd: %s, state: %s", cmdTopic.c_str(), stateTopic.c_str());
}
