//
// Ds18b20Handler.cpp
//

#include "Ds18b20Handler.h"

// Static member definitions
std::map<int, std::unique_ptr<OneWire>> Ds18b20Handler::oneWireInstances;
std::map<int, std::unique_ptr<DallasTemperature>> Ds18b20Handler::sensors;

DallasTemperature* Ds18b20Handler::getSensor(int pin) {
    auto it = sensors.find(pin);
    if (it != sensors.end()) {
        return it->second.get();
    }
    return nullptr;
}

void Ds18b20Handler::init(const PinConfig& cfg,
                           std::vector<MqttProducer>& producers,
                           std::vector<MqttConsumer>& consumers,
                           const String& clientId) {
    // 1. Init OneWire instance
    auto oneWire = std::make_unique<OneWire>(cfg.pin);
    
    // 2. Init DallasTemperature with pointer to OneWire
    auto sensor = std::make_unique<DallasTemperature>(oneWire.get());
    sensor->begin();

    if (sensor->getDeviceCount() == 0) {
        Serial.printf("[Init] Warning: No DS18B20 found on GPIO%d\n", cfg.pin);
    } else {
        Serial.printf("[Init] Found %d DS18B20 on GPIO%d\n", sensor->getDeviceCount(), cfg.pin);
    }

    // 3. Store unique_ptrs in maps to keep them alive
    oneWireInstances[cfg.pin] = std::move(oneWire);
    sensors[cfg.pin] = std::move(sensor);

    // 4. Create Producer
    String topic = "/" + clientId + "/ds18b20/" + cfg.name + "/temperature";
    
    producers.emplace_back(cfg.pin, topic, cfg.pollingInterval, 0,
        [](int pin) {
            DallasTemperature* ds = Ds18b20Handler::getSensor(pin);
            if (ds) {
                // Request temp (blocking by default)
                ds->requestTemperatures();
                float t = ds->getTempCByIndex(0);
                if (t == DEVICE_DISCONNECTED_C) return String("error");
                return String(t, 2);
            }
            return String("error");
        });

    Serial.printf("[Init] GPIO%d (%s) as DS18B20 -> topic %s\n",
                  cfg.pin, cfg.name.c_str(), topic.c_str());
}

