//
// Created by fubus on 12/10/25.
//

#pragma once
#include <Arduino.h>

/**
 * @brief Connects to a WiFi network using configuration loaded from a JSON file.
 * 
 * The configuration file should contain SSID, password, and optional static IP settings.
 * 
 * @param filename The path to the JSON configuration file (default: "/wifi_config.json").
 * @return true if connected successfully, false otherwise.
 */
bool connectToWiFi(const char* filename = "/wifi_config.json");
