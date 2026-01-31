//
// main.cpp
// Clean code refactoring for ESP32 IoT setup
//

#include <Arduino.h>
#include <SPIFFS.h>
#include <PinConfig.h>
#include <WiFiManager.h>
#include <MqttManager.h>
#include <PubSubClient.h>
#include <WiFi.h>
#include <Logger.h>
#include "handlers/FanHandler.h"
#include <DisplayManager.h>
#include "providers/MqttDataProvider.h"

static const char* TAG = "Setup";

WiFiClient wifiClient;               // deve vivere per tutta la durata
PubSubClient mqttClient(wifiClient); // MQTT client basato su WiFiClient
static std::vector<PinConfig> pinConfigs;

// ----------------------------
// Utility: retry a step until success
// ----------------------------
void waitForCondition(const char *stepName,
                      std::function<bool()> condition,
                      unsigned long retryDelay = 3000)
{
    while (!condition())
    {
        LOG_WARN(TAG, "%s failed. Retrying in %lu ms...", stepName, retryDelay);
        delay(retryDelay);
    }
    LOG_INFO(TAG, "%s OK", stepName);
}

// ----------------------------
// Load pin configuration file
// ----------------------------
bool loadPinConfiguration(const char *filename)
{
    pinConfigs = loadConfiguration(filename);
    if (pinConfigs.empty())
    {
        LOG_ERROR(TAG, "No pins defined in %s!", filename);
        return false;
    }
    return true;
}

// ----------------------------
// Register pins with MQTT manager
// ----------------------------
bool registerPinsToMqtt()
{
    if (pinConfigs.empty())
        return false;
    return MqttManager::registerPins(pinConfigs);
}

// ----------------------------
// Arduino setup()
// ----------------------------
void setup()
{
    LOG_INIT(115200);
    LOG_INFO(TAG, "Starting ESP32 IoT Application...");

    // --- Mount filesystem ---
    waitForCondition("SPIFFS mount", []()
                     { return SPIFFS.begin(true); });

    // --- Connect WiFi (WiFiManager wrapper reads /wifi_config.json) ---
    waitForCondition("WiFi connection", []()
                     { return connectToWiFi("/wifi_config.json"); });

    // --- Load pin config ---
    waitForCondition("Pin config load", []()
                     { return loadPinConfiguration("/pin_config.json"); });

    // --- Load MQTT config ---
    waitForCondition("MQTT config load", []()
                     { return MqttManager::loadConfig("/mqtt_config.json"); });

    // --- Register pins ---
    waitForCondition("Pin registration", []()
                     { return registerPinsToMqtt(); });

    // --- Connect to MQTT ---
    waitForCondition("MQTT connection", []()
                     { return MqttManager::connect(mqttClient); });

    // --- Initialize Display (optional - continues if disabled or not present) ---
    if (DisplayManager::loadConfig("/display_config.json")) {
        auto dataProvider = std::make_unique<MqttDataProvider>(pinConfigs);
        if (DisplayManager::init(std::move(dataProvider))) {
            LOG_INFO(TAG, "Display initialized");
        }
    }

    LOG_INFO(TAG, "System initialized successfully!");
}

// ----------------------------
// Arduino loop()
// ----------------------------
void loop()
{
    MqttManager::getInstance().loop();            // mantiene viva la connessione
    MqttManager::handleProducers(); // pubblica periodicamente i valori
    MqttManager::handleConsumers(); // watchdog per i consumer
    FanHandler::processKickstarts(); // process pending fan kickstart transitions
    DisplayManager::update(); // update display rotation and error detection
}