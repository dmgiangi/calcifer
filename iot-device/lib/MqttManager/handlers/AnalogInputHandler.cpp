//
// AnalogInputHandler.cpp
//

#include "AnalogInputHandler.h"

void AnalogInputHandler::init(const PinConfig& cfg,
                               std::vector<MqttProducer>& producers,
                               std::vector<MqttConsumer>& consumers,
                               const String& clientId) {
    analogReadResolution(12);
    analogSetPinAttenuation(cfg.pin, ADC_11db);
    
    String topic = "/" + clientId + "/analog_input/" + cfg.name + "/value";
    
    producers.emplace_back(cfg.pin, topic, cfg.pollingInterval, 0,
        [](int pin) { return String(analogRead(pin)); });
    
    Serial.printf("[Init] GPIO%d (%s) as INPUT_ANALOG -> topic %s\n",
                  cfg.pin, cfg.name.c_str(), topic.c_str());
}

