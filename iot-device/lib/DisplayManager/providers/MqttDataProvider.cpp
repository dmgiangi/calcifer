//
// MqttDataProvider.cpp - Data provider bridging MqttManager to DisplayManager
//

#include "MqttDataProvider.h"
#include <MqttManager.h>
#include <WiFi.h>
#include <Logger.h>

// Include handlers for static getState() access
#include "handlers/DigitalOutputHandler.h"
#include "handlers/PwmHandler.h"
#include "handlers/AnalogOutputHandler.h"
#include "handlers/FanHandler.h"
#include "handlers/Ds18b20Handler.h"
#include "handlers/Dht22Handler.h"
#include "handlers/ThermocoupleHandler.h"

// Include sensor libraries for direct access
#include <DHT.h>

static const char* TAG = "MqttDataProv";

MqttDataProvider::MqttDataProvider(const std::vector<PinConfig>& pinConfigs)
    : pinConfigs_(pinConfigs)
    , lastRefresh_(0)
{
}

std::vector<DisplayItem> MqttDataProvider::getDisplayableItems() {
    return cachedItems_;
}

ConnectionStatus MqttDataProvider::getConnectionStatus() {
    ConnectionStatus status;
    status.wifiConnected = (WiFi.status() == WL_CONNECTED);
    status.mqttConnected = MqttManager::getInstance().isConnected();
    
    if (!status.wifiConnected) {
        status.errorMessage = "WiFi Disconnected";
    } else if (!status.mqttConnected) {
        status.errorMessage = "MQTT Disconnected";
    }
    
    return status;
}

void MqttDataProvider::refresh() {
    cachedItems_.clear();
    
    for (const auto& cfg : pinConfigs_) {
        if (isActuatorMode(cfg.mode)) {
            addActuatorItem(cfg);
        } else {
            addSensorItem(cfg);
        }
    }
    
    lastRefresh_ = millis();
    LOG_DEBUG(TAG, "Refreshed %d display items", cachedItems_.size());
}

void MqttDataProvider::addSensorItem(const PinConfig& cfg) {
    String value = "---";
    String unit = getUnitForMode(cfg.mode);
    String typeStr = getDeviceTypeString(cfg.mode);
    
    // Read current value from the appropriate sensor
    switch (cfg.mode) {
        case DS18B20: {
            DallasTemperature* sensor = Ds18b20Handler::getSensor(cfg.pin);
            if (sensor) {
                sensor->requestTemperatures();
                float temp = sensor->getTempCByIndex(0);
                if (temp != DEVICE_DISCONNECTED_C) {
                    value = String(temp, 1);
                }
            }
            break;
        }
        case DHT22_SENSOR: {
            DHT* sensor = Dht22Handler::getSensor(cfg.pin);
            if (sensor) {
                float temp = sensor->readTemperature();
                float hum = sensor->readHumidity();
                if (!isnan(temp) && !isnan(hum)) {
                    // Show both temp and humidity
                    value = String(temp, 1) + "C " + String(hum, 0) + "%";
                    unit = "";  // Already included in value
                }
            }
            break;
        }
        case THERMOCOUPLE: {
            MAX6675* sensor = ThermocoupleHandler::getSensor(cfg.pin);
            if (sensor) {
                float temp = sensor->readCelsius();
                if (temp > 0) {  // MAX6675 returns 0 on error
                    value = String(temp, 1);
                }
            }
            break;
        }
        default:
            // For other sensor types, try to get from producer
            break;
    }
    
    cachedItems_.emplace_back(cfg.name, typeStr, value, unit, false);
}

void MqttDataProvider::addActuatorItem(const PinConfig& cfg) {
    String value = "---";
    String unit = getUnitForMode(cfg.mode);
    String typeStr = getDeviceTypeString(cfg.mode);
    
    // Get current state from handler's static method
    switch (cfg.mode) {
        case OUTPUT_DIGITAL:
            value = DigitalOutputHandler::getState(cfg.pin);
            value = (value == "1") ? "ON" : "OFF";
            break;
        case PWM:
            value = PwmHandler::getState(cfg.pin);
            break;
        case OUTPUT_ANALOG:
            value = AnalogOutputHandler::getState(cfg.pin);
            break;
        case FAN:
            value = FanHandler::getState(cfg.pin);
            break;
        default:
            break;
    }
    
    // For actuators, commanded value equals current state in this implementation
    cachedItems_.emplace_back(cfg.name, typeStr, value, unit, true, value);
}

String MqttDataProvider::getUnitForMode(PinModeType mode) {
    switch (mode) {
        case DS18B20:
        case THERMOCOUPLE:
            return "C";
        case DHT22_SENSOR:
            return "";  // Handled specially
        case YL_69_SENSOR:
        case FAN:
            return "%";
        case PWM:
        case OUTPUT_ANALOG:
            return "";
        default:
            return "";
    }
}

String MqttDataProvider::getDeviceTypeString(PinModeType mode) {
    switch (mode) {
        case INPUT_DIGITAL:     return "DI";
        case OUTPUT_DIGITAL:    return "DO";
        case PWM:               return "PWM";
        case INPUT_ANALOG:      return "AI";
        case OUTPUT_ANALOG:     return "AO";
        case DHT22_SENSOR:      return "DHT";
        case YL_69_SENSOR:      return "YL69";
        case DS18B20:           return "DS18";
        case THERMOCOUPLE:      return "TC";
        case FAN:               return "FAN";
        default:                return "?";
    }
}

bool MqttDataProvider::isActuatorMode(PinModeType mode) {
    return mode == OUTPUT_DIGITAL || 
           mode == PWM || 
           mode == OUTPUT_ANALOG || 
           mode == FAN;
}

