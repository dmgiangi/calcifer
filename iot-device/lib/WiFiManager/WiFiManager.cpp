//
// WiFiManager.cpp
// Clean code refactoring with ArduinoJson
//

#include "WiFiManager.h"
#include <WiFi.h>
#include <SPIFFS.h>
#include <ArduinoJson.h>
#include <Logger.h>

static const char* TAG = "WiFi";

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
        LOG_ERROR(TAG, "Config file %s not found!", filename);
        return config;
    }

    File file = SPIFFS.open(filename, "r");
    if (!file) {
        LOG_ERROR(TAG, "Failed to open file %s", filename);
        return config;
    }

    if (file.size() == 0) {
        LOG_ERROR(TAG, "Config file %s is empty!", filename);
        file.close();
        return config;
    }

    // Read content into String to avoid stream issues
    String fileContent = file.readString();
    file.close();

    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, fileContent);

    if (err) {
        LOG_ERROR(TAG, "JSON parse error in %s: %s", filename, err.c_str());
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
        LOG_ERROR(TAG, "SSID missing!");
        return false;
    }

    WiFi.mode(WIFI_STA);

    // DHCP vs Static IP configuration
    if (config.useDhcp) {
        LOG_INFO(TAG, "Using DHCP");
    } else {
        if (!config.ip.isEmpty() && !config.gateway.isEmpty() && !config.subnet.isEmpty()) {
            IPAddress ip, gateway, subnet, dns;
            if (ip.fromString(config.ip) && gateway.fromString(config.gateway) && subnet.fromString(config.subnet)) {
                 dns = config.dns.isEmpty() ? IPAddress(8, 8, 8, 8) : IPAddress();
                 if (!config.dns.isEmpty()) dns.fromString(config.dns);

                 if (!WiFi.config(ip, gateway, subnet, dns)) {
                     LOG_ERROR(TAG, "Failed to configure static IP");
                 } else {
                     LOG_INFO(TAG, "Static IP: %s", ip.toString().c_str());
                 }
            } else {
                LOG_ERROR(TAG, "Invalid IP address format in config");
            }
        } else {
            LOG_WARN(TAG, "Missing static IP fields, fallback to DHCP");
        }
    }

    // Start WiFi connection
    WiFi.begin(config.ssid.c_str(), config.password.c_str());

    LOG_INFO(TAG, "Connecting to %s...", config.ssid.c_str());
    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED && (millis() - start) < (unsigned long)config.connectTimeout) {
        delay(500);
    }

    if (WiFi.status() == WL_CONNECTED) {
        LOG_INFO(TAG, "Connected! IP: %s", WiFi.localIP().toString().c_str());
        return true;
    }

    LOG_ERROR(TAG, "Connection failed.");
    return false;
}