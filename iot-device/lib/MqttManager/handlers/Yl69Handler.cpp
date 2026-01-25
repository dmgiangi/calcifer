//
// Yl69Handler.cpp
//

#include "Yl69Handler.h"
#include <Logger.h>

static const char* TAG = "YL69";

void Yl69Handler::init(const PinConfig& cfg,
                        std::vector<MqttProducer>& producers,
                        std::vector<MqttConsumer>& consumers,
                        const String& clientId) {
    analogReadResolution(12);
    analogSetPinAttenuation(cfg.pin, ADC_11db);
    
    String topic = "/" + clientId + "/yl69/" + cfg.name + "/value";
    
    producers.emplace_back(cfg.pin, topic, cfg.pollingInterval, 0,
        [](int pin) {
            int raw = analogRead(pin);
            // YL-69: High value = Dry, Low value = Wet (usually)
            int percent = map(raw, 0, 4095, 100, 0);
            return String(percent);
        });
    
    LOG_INFO(TAG, "GPIO%d (%s) -> topic %s",
             cfg.pin, cfg.name.c_str(), topic.c_str());
}

