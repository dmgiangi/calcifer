//
// DigitalOutputHandler.cpp
//

#include "DigitalOutputHandler.h"
#include <map>

// Static map to store current state per pin (for state publishing)
std::map<int, String> DigitalOutputHandler::currentState;

String DigitalOutputHandler::getState(int pin) {
    auto it = currentState.find(pin);
    return (it != currentState.end()) ? it->second : String("0");
}

void DigitalOutputHandler::setState(int pin, const String& value) {
    currentState[pin] = value;
}

void DigitalOutputHandler::init(const PinConfig& cfg,
                                 std::vector<MqttProducer>& producers,
                                 std::vector<MqttConsumer>& consumers,
                                 const String& clientId) {
    pinMode(cfg.pin, OUTPUT);

    // Apply inversion to default state
    int physicalDefault = cfg.defaultState;
    if (cfg.inverted) physicalDefault = !physicalDefault;
    digitalWrite(cfg.pin, physicalDefault);

    // Initialize state in static map
    currentState[cfg.pin] = String(cfg.defaultState);

    String cmdTopic = "/" + clientId + "/digital_output/" + cfg.name + "/set";
    String stateTopic = "/" + clientId + "/digital_output/" + cfg.name + "/state";

    MqttConsumer c;
    c.pin = cfg.pin;
    c.topic = cmdTopic;
    c.lastValue = String(cfg.defaultState);
    c.fallbackValue = String(cfg.defaultState);
    c.interval = cfg.pollingInterval;
    c.lastUpdate = millis();

    bool inverted = cfg.inverted;
    int pin = cfg.pin;
    c.onMessage = [inverted, pin](int p, const String &msg) {
        int logicalValue = (msg == "1" || msg == "HIGH") ? HIGH : LOW;
        int physicalValue = (inverted) ? !logicalValue : logicalValue;
        digitalWrite(p, physicalValue);
        // Update static state map
        DigitalOutputHandler::setState(pin, String(logicalValue));
        Serial.printf("[Consumer] GPIO%d set to %d (Physical: %d) via MQTT\n",
                      p, logicalValue, physicalValue);
    };

    consumers.push_back(std::move(c));

    // Add producer for state publishing
    if (cfg.pollingInterval > 0) {
        producers.emplace_back(cfg.pin, stateTopic, cfg.pollingInterval, 0,
            [](int pin) {
                return DigitalOutputHandler::getState(pin);
            });
    }

    Serial.printf("[Init] GPIO%d (%s) as OUTPUT_DIGITAL -> cmd: %s, state: %s, default=%d (Inverted: %s)\n",
                  cfg.pin, cfg.name.c_str(), cmdTopic.c_str(), stateTopic.c_str(), cfg.defaultState, inverted ? "Yes" : "No");
}

