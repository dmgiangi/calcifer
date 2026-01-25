#include "MqttManager.h"
#include "handlers/DeviceHandlerRegistry.h"
#include <SPIFFS.h>
#include <ArduinoJson.h>

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
        Serial.printf("[MQTT] Config file %s not found or empty!\n", filename);
        return false;
    }

    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, file);
    file.close();

    if (err)
    {
        Serial.printf("[MQTT] JSON parse error: %s\n", err.c_str());
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
        Serial.println("[MQTT] Missing host in config");
        return false;
    }

    Serial.println("[MQTT] ========== CONFIGURAZIONE ==========");
    Serial.printf("[MQTT] Host: %s\n", instance.mqttHost.c_str());
    Serial.printf("[MQTT] Porta: %d\n", instance.mqttPort);
    Serial.printf("[MQTT] Client ID: %s\n", instance.mqttClientId.c_str());
    Serial.printf("[MQTT] Username: %s\n", instance.mqttUsername.isEmpty() ? "<vuoto>" : instance.mqttUsername.c_str());
    Serial.printf("[MQTT] Password: %s\n", instance.mqttPassword.isEmpty() ? "<vuota>" : "<impostata>");
    Serial.printf("[MQTT] Keep Alive: %d sec\n", instance.mqttKeepAlive);
    Serial.println("[MQTT] ======================================");

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
        Serial.print("[MQTT] Tentativo di connessione senza credenziali...");
        connected = instance.mqttClient->connect(instance.mqttClientId.c_str());
    }
    else
    {
        Serial.print("[MQTT] Tentativo di connessione con credenziali...");
        connected = instance.mqttClient->connect(
            instance.mqttClientId.c_str(),
            instance.mqttUsername.c_str(),
            instance.mqttPassword.c_str());
    }

    if (connected)
    {
        Serial.println(" Connesso!");
        Serial.printf("[MQTT] Sottoscrizione a %d topic...\n", instance.consumers.size());
        for (auto &c : instance.consumers)
        {
            if (instance.mqttClient->subscribe(c.topic.c_str()))
            {
                Serial.printf("[MQTT] Subscribed to: %s\n", c.topic.c_str());
            }
            else
            {
                Serial.printf("[MQTT] Failed to subscribe to: %s\n", c.topic.c_str());
            }
        }
    }
    else
    {
        Serial.print(" Fallito, rc=");
        Serial.println(instance.mqttClient->state());
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

    Serial.printf("[MQTT] Messaggio ricevuto su topic: %s -> %s\n", t.c_str(), msg.c_str());
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
                    Serial.printf("[MQTT Producer] %s -> %s\n", p.topic.c_str(), value.c_str());
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
            Serial.printf("[Consumer Watchdog] GPIO%d reset to fallback %s\n",
                          c.pin, c.fallbackValue.c_str());
        }
    }
}

// ----------------------
// Device Registration
// ----------------------
bool MqttManager::registerPins(const std::vector<PinConfig> &configs)
{
    MqttManager& instance = getInstance();
    Serial.printf("[MQTT] Registrazione di %d pin...\n", configs.size());

    // Initialize the device handler registry with all default handlers
    DeviceHandlerRegistry::registerDefaultHandlers();

    // Use the registry to initialize each device
    for (const auto &cfg : configs)
    {
        if (!DeviceHandlerRegistry::initDevice(cfg, instance.producers, instance.consumers, instance.mqttClientId))
        {
            Serial.printf("[Init] Unknown mode for GPIO%d (%s)\n", cfg.pin, cfg.name.c_str());
        }
    }

    Serial.printf("[MQTT] Registrati %d producer e %d consumer\n",
                  instance.producers.size(), instance.consumers.size());
    return true;
}