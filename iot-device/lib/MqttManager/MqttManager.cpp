#include "MqttManager.h"
#include "handlers/DeviceHandlerRegistry.h"
#include <SPIFFS.h>
#include <ArduinoJson.h>
#include <Logger.h>

static const char* TAG = "MQTT";

// ----------------------
// Static/Global Trampoline
// ----------------------
static void globalMqttCallback(char *topic, byte *payload, unsigned int length)
{
    MqttManager::onMqttMessage(topic, payload, length);
}

// ----------------------
// Singleton Instance
// ----------------------
MqttManager &MqttManager::getInstance()
{
    static MqttManager instance;
    return instance;
}
// ----------------------
// Configuration
// ----------------------
bool MqttManager::loadConfig(const char *filename)
{
    MqttManager& instance = getInstance();
    File file = SPIFFS.open(filename, "r");
    if (!file || file.size() == 0)
    {
        LOG_ERROR(TAG, "Config file %s not found or empty!", filename);
        return false;
    }

    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, file);
    file.close();

    if (err)
    {
        LOG_ERROR(TAG, "JSON parse error: %s", err.c_str());
        return false;
    }

    instance.mqttHost = doc["host"] | "";
    instance.mqttPort = doc["port"] | 1883;
    instance.mqttClientId = doc["clientId"] | "ESP32Client";
    instance.mqttUsername = doc["username"] | "";
    instance.mqttPassword = doc["password"] | "";
    instance.mqttKeepAlive = doc["keepAlive"] | 15;

    if (instance.mqttHost.isEmpty())
    {
        LOG_ERROR(TAG, "Missing host in config");
        return false;
    }

    LOG_INFO(TAG, "========== CONFIGURATION ==========");
    LOG_INFO(TAG, "Host: %s", instance.mqttHost.c_str());
    LOG_INFO(TAG, "Port: %d", instance.mqttPort);
    LOG_INFO(TAG, "Client ID: %s", instance.mqttClientId.c_str());
    LOG_INFO(TAG, "Username: %s", instance.mqttUsername.isEmpty() ? "<empty>" : instance.mqttUsername.c_str());
    LOG_INFO(TAG, "Password: %s", instance.mqttPassword.isEmpty() ? "<empty>" : "<set>");
    LOG_INFO(TAG, "Keep Alive: %d sec", instance.mqttKeepAlive);
    LOG_INFO(TAG, "====================================");

    return true;
}

// ----------------------
// Connection
// ----------------------
bool MqttManager::connect(PubSubClient &client)
{
    MqttManager& instance = getInstance();
    instance.mqttClient = &client;
    client.setServer(instance.mqttHost.c_str(), instance.mqttPort);
    client.setCallback(globalMqttCallback);
    client.setKeepAlive(instance.mqttKeepAlive);
    client.setSocketTimeout(15);

    return reconnect();
}

bool MqttManager::reconnect()
{
    MqttManager& instance = getInstance();
    if (!instance.mqttClient) return false;
    if (instance.mqttClient->connected()) return true;

    bool connected = false;
    if (instance.mqttUsername.isEmpty())
    {
        LOG_DEBUG(TAG, "Connecting without credentials...");
        connected = instance.mqttClient->connect(instance.mqttClientId.c_str());
    }
    else
    {
        LOG_DEBUG(TAG, "Connecting with credentials...");
        connected = instance.mqttClient->connect(
            instance.mqttClientId.c_str(),
            instance.mqttUsername.c_str(),
            instance.mqttPassword.c_str());
    }

    if (connected)
    {
        LOG_INFO(TAG, "Connected!");
        LOG_DEBUG(TAG, "Subscribing to %d topics...", instance.consumers.size());
        for (auto &c : instance.consumers)
        {
            if (instance.mqttClient->subscribe(c.topic.c_str()))
            {
                LOG_DEBUG(TAG, "Subscribed to: %s", c.topic.c_str());
            }
            else
            {
                LOG_WARN(TAG, "Failed to subscribe to: %s", c.topic.c_str());
            }
        }
    }
    else
    {
        LOG_ERROR(TAG, "Connection failed, rc=%d", instance.mqttClient->state());
    }

    return connected;
}

void MqttManager::loop()
{
    MqttManager& instance = getInstance();
    if (!instance.mqttClient)
        return;

    if (!instance.mqttClient->connected())
    {
        unsigned long now = millis();
        if (now - instance.lastReconnectAttempt > 5000)
        {
            instance.lastReconnectAttempt = now;
            reconnect();
        }
    }
    else
    {
        instance.mqttClient->loop();
    }
}

// ----------------------
// Message Handling
// ----------------------
void MqttManager::onMqttMessage(char *topic, byte *payload, unsigned int length)
{
    String msg;
    for (unsigned int i = 0; i < length; i++)
    {
        msg += (char)payload[i];
    }
    String t(topic);

    LOG_DEBUG(TAG, "Message received: %s -> %s", t.c_str(), msg.c_str());
    processMessage(t, msg);
}

void MqttManager::processMessage(const String &topic, const String &payload)
{
    MqttManager& instance = getInstance();
    for (auto &c : instance.consumers)
    {
        if (c.topic == topic)
        {
            if (c.onMessage) {
                c.onMessage(c.pin, payload);
            }
            c.lastValue = payload;
            c.lastUpdate = millis();
            break;
        }
    }
}

// ----------------------
// Producers & Consumers
// ----------------------
void MqttManager::handleProducers()
{
    MqttManager& instance = getInstance();
    if (!instance.mqttClient || !instance.mqttClient->connected())
        return;

    unsigned long now = millis();

    for (auto &p : instance.producers)
    {
        if (now - p.lastPublish >= p.interval)
        {
            p.lastPublish = now;
            if (p.readFn) {
                String value = p.readFn(p.pin);
                if (instance.mqttClient->publish(p.topic.c_str(), value.c_str(), true))
                {
                    LOG_DEBUG(TAG, "Producer: %s -> %s", p.topic.c_str(), value.c_str());
                }
            }
        }
    }
}

void MqttManager::handleConsumers()
{
    MqttManager& instance = getInstance();
    unsigned long now = millis();
    for (auto &c : instance.consumers)
    {
        if (!c.interval)
            continue;
        if (now - c.lastUpdate > c.interval)
        {
            if (c.onMessage) {
                c.onMessage(c.pin, c.fallbackValue);
            }
            c.lastValue = c.fallbackValue;
            c.lastUpdate = now;
            LOG_WARN(TAG, "Watchdog: GPIO%d reset to fallback %s", c.pin, c.fallbackValue.c_str());
        }
    }
}

// ----------------------
// Device Registration
// ----------------------
bool MqttManager::registerPins(const std::vector<PinConfig> &configs)
{
    MqttManager& instance = getInstance();
    LOG_INFO(TAG, "Registering %d pins...", configs.size());

    // Initialize the device handler registry with all default handlers
    DeviceHandlerRegistry::registerDefaultHandlers();

    // Use the registry to initialize each device
    for (const auto &cfg : configs)
    {
        if (!DeviceHandlerRegistry::initDevice(cfg, instance.producers, instance.consumers, instance.mqttClientId))
        {
            LOG_WARN(TAG, "Unknown mode for GPIO%d (%s)", cfg.pin, cfg.name.c_str());
        }
    }

    LOG_INFO(TAG, "Registered %d producers and %d consumers",
             instance.producers.size(), instance.consumers.size());
    return true;
}