//
// AnalogOutputHandler.cpp
//

#include "AnalogOutputHandler.h"
#include <map>
#include <Logger.h>

static const char* TAG = "AnalogOutput";

// Static map to store current state per pin (for state publishing)
std::map<int, String> AnalogOutputHandler::currentState;

String AnalogOutputHandler::getState(int pin) {
    auto it = currentState.find(pin);
    return (it != currentState.end()) ? it->second : String("0");
}

void AnalogOutputHandler::setState(int pin, const String& value) {
    currentState[pin] = value;
}

void AnalogOutputHandler::init(const PinConfig& cfg,
                                std::vector<MqttProducer>& producers,
                                std::vector<MqttConsumer>& consumers,
                                const String& clientId) {
    dacWrite(cfg.pin, cfg.defaultState);

    // Initialize state in static map
    currentState[cfg.pin] = String(cfg.defaultState);

    String cmdTopic = "/" + clientId + "/analog_output/" + cfg.name + "/set";
    String stateTopic = "/" + clientId + "/analog_output/" + cfg.name + "/state";

    int pin = cfg.pin;
    auto c = MqttConsumer::createForActuator(cfg, cmdTopic,
        [pin](int p, const String &msg) {
            int value = constrain(msg.toInt(), 0, 255);
            dacWrite(p, value);
            // Update static state map
            AnalogOutputHandler::setState(pin, String(value));
            LOG_DEBUG(TAG, "GPIO%d DAC <- %d", p, value);
        });

    consumers.push_back(std::move(c));

    // Add producer for state publishing
    if (cfg.pollingInterval > 0) {
        producers.emplace_back(cfg.pin, stateTopic, cfg.pollingInterval, 0,
            [](int pin) {
                return AnalogOutputHandler::getState(pin);
            });
    }

    LOG_INFO(TAG, "GPIO%d (%s) -> cmd: %s, state: %s",
             cfg.pin, cfg.name.c_str(), cmdTopic.c_str(), stateTopic.c_str());
}

