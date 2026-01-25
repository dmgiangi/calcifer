//
// DigitalInputHandler.cpp
//

#include "DigitalInputHandler.h"
#include <Logger.h>

static const char* TAG = "DigitalInput";

void DigitalInputHandler::init(const PinConfig& cfg,
                                std::vector<MqttProducer>& producers,
                                std::vector<MqttConsumer>& consumers,
                                const String& clientId) {
    pinMode(cfg.pin, INPUT_PULLUP);
    
    String topic = "/" + clientId + "/digital_input/" + cfg.name + "/value";
    bool inverted = cfg.inverted;
    
    producers.emplace_back(cfg.pin, topic, cfg.pollingInterval, 0,
        [inverted](int pin) {
            bool val = digitalRead(pin);
            if (inverted) val = !val;
            return String(val);
        });
    
    LOG_INFO(TAG, "GPIO%d (%s) -> topic %s (Inverted: %s)",
             cfg.pin, cfg.name.c_str(), topic.c_str(), inverted ? "Yes" : "No");
}

