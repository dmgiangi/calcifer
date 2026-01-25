//
// Ds18b20Handler.cpp
//

#include "Ds18b20Handler.h"
#include <Logger.h>

static const char* TAG = "DS18B20";

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
        LOG_WARN(TAG, "No DS18B20 found on GPIO%d", cfg.pin);
    } else {
        LOG_INFO(TAG, "Found %d DS18B20 on GPIO%d", sensor->getDeviceCount(), cfg.pin);
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

    LOG_INFO(TAG, "GPIO%d (%s) -> topic %s",
             cfg.pin, cfg.name.c_str(), topic.c_str());
}

