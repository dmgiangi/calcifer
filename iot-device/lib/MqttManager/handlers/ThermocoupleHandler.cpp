//
// ThermocoupleHandler.cpp
//

#include "ThermocoupleHandler.h"
#include <Logger.h>

static const char* TAG = "Thermocouple";

// Static member definition
std::map<int, std::unique_ptr<MAX6675>> ThermocoupleHandler::sensors;

MAX6675* ThermocoupleHandler::getSensor(int pin) {
    auto it = sensors.find(pin);
    if (it != sensors.end()) {
        return it->second.get();
    }
    return nullptr;
}

void ThermocoupleHandler::init(const PinConfig& cfg,
                                std::vector<MqttProducer>& producers,
                                std::vector<MqttConsumer>& consumers,
                                const String& clientId) {
    // MAX6675(int8_t SCLK, int8_t CS, int8_t MISO);
    auto sensor = std::make_unique<MAX6675>(cfg.pinClock, cfg.pin, cfg.pinData);
    
    // Store in map
    sensors[cfg.pin] = std::move(sensor);

    String topic = "/" + clientId + "/thermocouple/" + cfg.name + "/temperature";
    
    producers.emplace_back(cfg.pin, topic, cfg.pollingInterval, 0,
        [](int pin) {
            MAX6675* tc = ThermocoupleHandler::getSensor(pin);
            if (tc) {
                float t = tc->readCelsius();
                return isnan(t) ? String("error") : String(t, 2);
            }
            return String("error");
        });

    LOG_INFO(TAG, "GPIO%d (%s) (CS:%d, SCK:%d, SO:%d) -> topic %s",
             cfg.pin, cfg.name.c_str(), cfg.pin, cfg.pinClock, cfg.pinData, topic.c_str());
}

