//
// Created by fubus on 12/10/25.
// Refactored for Clean Code & SOLID on 18/01/26.
// Refactored with Strategy Pattern for device handlers on 25/01/26.
//

#pragma once

#include <Arduino.h>
#include <vector>
#include <PinConfig.h>
#include <PubSubClient.h>
#include "handlers/IDeviceHandler.h"

/**
 * @brief Singleton Manager for MQTT operations and message routing.
 *
 * Responsibilities:
 * - Load MQTT configuration.
 * - Manage MQTT connection (reconnect logic).
 * - Register hardware pins via DeviceHandlerRegistry.
 * - Dispatch incoming messages to Consumers.
 * - Poll Producers to publish messages.
 *
 * Device-specific initialization is delegated to handlers in the
 * handlers/ directory, following the Strategy Pattern.
 */
class MqttManager {
public:
    // Singleton Access
    static MqttManager& getInstance();

    // Prevent copy/move
    MqttManager(const MqttManager&) = delete;
    MqttManager& operator=(const MqttManager&) = delete;

    // Lifecycle & Config
    static bool loadConfig(const char *filename);
    static bool registerPins(const std::vector<PinConfig> &configs);
    static bool connect(PubSubClient &client);

    // Getters for testing/verification
    String getMqttHost() const { return mqttHost; }
    int getMqttPort() const { return mqttPort; }
    String getClientId() const { return mqttClientId; }

    // Getters for DisplayManager integration
    bool isConnected() const { return mqttClient && mqttClient->connected(); }
    const std::vector<MqttProducer>& getProducers() const { return producers; }
    const std::vector<MqttConsumer>& getConsumers() const { return consumers; }

    // Main Loop
    static void loop();
    static void handleProducers();
    static void handleConsumers();

    // Internal callback trampoline (public so generic callback can access it)
    static void onMqttMessage(char *topic, byte *payload, unsigned int length);

private:
    MqttManager() = default;
    ~MqttManager() = default;

    // Internal Helpers
    static bool reconnect();
    static void processMessage(const String &topic, const String &payload);

    // State
    PubSubClient *mqttClient = nullptr;
    std::vector<MqttConsumer> consumers;
    std::vector<MqttProducer> producers;

    unsigned long lastReconnectAttempt = 0;

    // Configuration
    String mqttHost;
    int mqttPort = 1883;
    String mqttClientId = "ESP32Client";
    String mqttUsername;
    String mqttPassword;
    int mqttKeepAlive = 15;
};