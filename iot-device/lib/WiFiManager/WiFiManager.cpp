//
// WiFiManager.cpp
// Clean code refactoring with ArduinoJson
//

#include "WiFiManager.h"
#include <WiFi.h>
#include <SPIFFS.h>
#include <ArduinoJson.h>

// ----------------------------
// Data structure for WiFi config
// ----------------------------
struct WiFiConfig {
    String ssid;
    String password;
    bool useDhcp;
    String ip;
    String gateway;
    String subnet;
    String dns;
    int connectTimeout;
};

// ----------------------------
// Load WiFi configuration from JSON file
// ----------------------------
static WiFiConfig loadWiFiConfig(const char *filename) {
    WiFiConfig config = {"", "", true, "", "", "", "", 15000};

    if (!SPIFFS.exists(filename)) {
        Serial.printf("[WiFiManager] Config file %s not found!\n", filename);
        return config;
    }

    File file = SPIFFS.open(filename, "r");
    if (!file) {
        Serial.printf("[WiFiManager] Failed to open file %s\n", filename);
        return config;
    }

    if (file.size() == 0) {
        Serial.printf("[WiFiManager] Config file %s is empty!\n", filename);
        file.close();
        return config;
    }

    // Read content into String to avoid stream issues
    String fileContent = file.readString();
    file.close();

    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, fileContent);

    if (err) {
        Serial.printf("[WiFiManager] JSON parse error in %s: %s\n", filename, err.c_str());
        return config;
    }

    config.ssid           = doc["ssid"]           | "";
    config.password       = doc["password"]       | "";
    config.useDhcp        = doc["useDhcp"]        | true;
    config.ip             = doc["ip"]             | "";
    config.gateway        = doc["gateway"]        | "";
    config.subnet         = doc["subnet"]         | "";
    config.dns            = doc["dns"]            | "";
    config.connectTimeout = doc["connectTimeout"] | 15000;

    return config;
}

// ----------------------------
// Connect to WiFi using configuration
// ----------------------------
bool connectToWiFi(const char *filename) {
    WiFiConfig config = loadWiFiConfig(filename);

    if (config.ssid.isEmpty()) {
        Serial.println("[WiFiManager] SSID missing!");
        return false;
    }

    WiFi.mode(WIFI_STA);

    // DHCP vs Static IP configuration
    if (config.useDhcp) {
        Serial.println("[WiFiManager] Using DHCP");
    } else {
        if (!config.ip.isEmpty() && !config.gateway.isEmpty() && !config.subnet.isEmpty()) {
            IPAddress ip, gateway, subnet, dns;
            if (ip.fromString(config.ip) && gateway.fromString(config.gateway) && subnet.fromString(config.subnet)) {
                 dns = config.dns.isEmpty() ? IPAddress(8, 8, 8, 8) : IPAddress();
                 if (!config.dns.isEmpty()) dns.fromString(config.dns);

                 if (!WiFi.config(ip, gateway, subnet, dns)) {
                     Serial.println("[WiFiManager] Failed to configure static IP");
                 } else {
                     Serial.printf("[WiFiManager] Static IP: %s\n", ip.toString().c_str());
                 }
            } else {
                Serial.println("[WiFiManager] Invalid IP address format in config");
            }
        } else {
            Serial.println("[WiFiManager] Missing static IP fields, fallback to DHCP");
        }
    }

    // Start WiFi connection
    WiFi.begin(config.ssid.c_str(), config.password.c_str());

    Serial.printf("[WiFiManager] Connecting to %s", config.ssid.c_str());
    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED && (millis() - start) < (unsigned long)config.connectTimeout) {
        delay(500);
        Serial.print(".");
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[WiFiManager] Connected! IP: %s\n",
                      WiFi.localIP().toString().c_str());
        return true;
    }

    Serial.println("[WiFiManager] Connection failed.");
    return false;
}