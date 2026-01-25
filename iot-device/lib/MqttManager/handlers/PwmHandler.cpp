//
// PwmHandler.cpp
//

#include "PwmHandler.h"
#include <map>

// Static map to store current state per pin (for state publishing)
std::map<int, String> PwmHandler::currentState;

String PwmHandler::getState(int pin) {
    auto it = currentState.find(pin);
    return (it != currentState.end()) ? it->second : String("0");
}

void PwmHandler::setState(int pin, const String& value) {
    currentState[pin] = value;
}

void PwmHandler::init(const PinConfig& cfg,
                       std::vector<MqttProducer>& producers,
                       std::vector<MqttConsumer>& consumers,
                       const String& clientId) {
    if (!nextPwmChannel || *nextPwmChannel >= 16) {
        Serial.printf("[Init] No PWM channels available, GPIO%d skipped\n", cfg.pin);
        return;
    }

    int channel = (*nextPwmChannel)++;
    ledcSetup(channel, 5000, 8);
    ledcAttachPin(cfg.pin, channel);
    ledcWrite(channel, cfg.defaultState);

    // Initialize state in static map
    currentState[cfg.pin] = String(cfg.defaultState);

    String cmdTopic = "/" + clientId + "/pwm/" + cfg.name + "/set";
    String stateTopic = "/" + clientId + "/pwm/" + cfg.name + "/state";

    MqttConsumer c;
    c.pin = cfg.pin;
    c.topic = cmdTopic;
    c.lastValue = String(cfg.defaultState);
    c.fallbackValue = String(cfg.defaultState);
    c.interval = cfg.pollingInterval;
    c.lastUpdate = millis();

    // Capture channel and pin by value
    int pin = cfg.pin;
    c.onMessage = [channel, pin](int p, const String &msg) {
        int duty = constrain(msg.toInt(), 0, 255);
        ledcWrite(channel, duty);
        // Update static state map
        PwmHandler::setState(pin, String(duty));
        Serial.printf("[Consumer] PWM ch %d duty <- %d\n", channel, duty);
    };

    consumers.push_back(std::move(c));

    // Add producer for state publishing
    if (cfg.pollingInterval > 0) {
        producers.emplace_back(cfg.pin, stateTopic, cfg.pollingInterval, 0,
            [](int pin) {
                return PwmHandler::getState(pin);
            });
    }

    Serial.printf("[Init] GPIO%d (%s) as PWM -> cmd: %s, state: %s, channel=%d, default=%d\n",
                  cfg.pin, cfg.name.c_str(), cmdTopic.c_str(), stateTopic.c_str(), channel, cfg.defaultState);
}

