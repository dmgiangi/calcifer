//
// Dht22Handler.cpp
//

#include "Dht22Handler.h"
#include <DHT.h>
#include <Logger.h>

static const char* TAG = "DHT22";

// Static member definition
std::map<int, std::unique_ptr<DHT>> Dht22Handler::sensors;

DHT* Dht22Handler::getSensor(int pin) {
    auto it = sensors.find(pin);
    if (it != sensors.end()) {
        return it->second.get();
    }
    return nullptr;
}

void Dht22Handler::init(const PinConfig& cfg,
                         std::vector<MqttProducer>& producers,
                         std::vector<MqttConsumer>& consumers,
                         const String& clientId) {
    auto sensor = std::make_unique<DHT>(cfg.pin, DHT22);
    sensor->begin();
    sensors[cfg.pin] = std::move(sensor);

    String topicTemp = "/" + clientId + "/dht22/" + cfg.name + "/temperature";
    String topicHum = "/" + clientId + "/dht22/" + cfg.name + "/humidity";

    // Temperature producer
    producers.emplace_back(cfg.pin, topicTemp, cfg.pollingInterval, 0,
        [](int pin) {
            DHT* dht = Dht22Handler::getSensor(pin);
            if (dht) {
                float t = dht->readTemperature();
                return isnan(t) ? String("nan") : String(t, 2);
            }
            return String("error");
        });

    // Humidity producer
    producers.emplace_back(cfg.pin, topicHum, cfg.pollingInterval, 0,
        [](int pin) {
            DHT* dht = Dht22Handler::getSensor(pin);
            if (dht) {
                float h = dht->readHumidity();
                return isnan(h) ? String("nan") : String(h, 2);
            }
            return String("error");
        });

    LOG_INFO(TAG, "GPIO%d (%s) -> topics %s, %s",
             cfg.pin, cfg.name.c_str(), topicTemp.c_str(), topicHum.c_str());
}

