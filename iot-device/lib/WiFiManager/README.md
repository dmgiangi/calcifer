---
title: "WiFiManager Library"
subtitle: "WiFi Connection Management for ESP32"
author: "Calcifer Team"
date: "\\today"
lang: "en"
titlepage: true
titlepage-color: "0B2C4B"
titlepage-text-color: "FFFFFF"
titlepage-rule-color: "E63946"
titlepage-rule-height: 2
toc: true
toc-own-page: true
listings: true
---

# WiFiManager Library

Handles WiFi connection for ESP32 devices using JSON-based configuration loaded from SPIFFS. Supports both DHCP and static IP configurations with automatic reconnection handling.

## Overview

The WiFiManager library provides:
- **JSON Configuration**: Load WiFi settings from SPIFFS filesystem
- **DHCP/Static IP**: Flexible network configuration options
- **Connection Timeout**: Configurable timeout for connection attempts
- **Error Handling**: Graceful fallbacks and detailed logging

## Configuration Structure

### WiFiConfig (Internal Struct)

```cpp
struct WiFiConfig {
    String ssid;           // WiFi network name
    String password;       // WiFi password
    bool useDhcp;          // true = DHCP, false = Static IP
    String ip;             // Static IP address
    String gateway;        // Gateway address
    String subnet;         // Subnet mask
    String dns;            // DNS server (optional, defaults to 8.8.8.8)
    int connectTimeout;    // Connection timeout in milliseconds
};
```

## JSON Configuration Schema

The `wifi_config.json` file defines network settings:

### Minimal Configuration (DHCP)

```json
{
  "ssid": "MyNetwork",
  "password": "MyPassword"
}
```

### Full Configuration (Static IP)

```json
{
  "ssid": "MyNetwork",
  "password": "MyPassword",
  "useDhcp": false,
  "ip": "192.168.1.100",
  "gateway": "192.168.1.1",
  "subnet": "255.255.255.0",
  "dns": "8.8.8.8",
  "connectTimeout": 15000
}
```

### Field Reference

| Field | Type | Required | Default | Description |
|:------|:-----|:--------:|:-------:|:------------|
| `ssid` | string | ✓ | - | WiFi network name |
| `password` | string | ✓ | - | WiFi password |
| `useDhcp` | bool | - | `true` | Use DHCP for IP assignment |
| `ip` | string | Static IP | - | Device IP address |
| `gateway` | string | Static IP | - | Network gateway |
| `subnet` | string | Static IP | - | Subnet mask |
| `dns` | string | - | `8.8.8.8` | DNS server |
| `connectTimeout` | int | - | `15000` | Timeout in ms |

## API Reference

### `connectToWiFi(filename)`

Connects to WiFi using configuration from a JSON file.

```cpp
bool connectToWiFi(const char* filename = "/wifi_config.json");
```

**Parameters:**
- `filename`: Path to JSON configuration file (default: `"/wifi_config.json"`)

**Returns:** 
- `true`: Successfully connected to WiFi
- `false`: Connection failed (timeout, missing SSID, or file error)

**Behavior:**
1. Loads configuration from SPIFFS
2. Sets WiFi mode to Station (STA)
3. Configures static IP if `useDhcp: false` and IP fields are valid
4. Initiates connection with timeout
5. Logs connection status and assigned IP

## Connection Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    connectToWiFi()                          │
├─────────────────────────────────────────────────────────────┤
│  1. Load JSON from SPIFFS                                   │
│     └─> If file missing/invalid → return false              │
│                                                             │
│  2. Validate SSID                                           │
│     └─> If empty → return false                             │
│                                                             │
│  3. Configure IP Mode                                       │
│     ├─> useDhcp: true  → Use DHCP                          │
│     └─> useDhcp: false → Configure static IP               │
│         └─> If IP fields invalid → Fallback to DHCP        │
│                                                             │
│  4. WiFi.begin(ssid, password)                              │
│     └─> Wait up to connectTimeout ms                        │
│                                                             │
│  5. Check connection status                                 │
│     ├─> Connected → Log IP, return true                    │
│     └─> Timeout   → Log failure, return false              │
└─────────────────────────────────────────────────────────────┘
```

## Usage Example

```cpp
#include <SPIFFS.h>
#include <WiFiManager.h>

void setup() {
    Serial.begin(115200);
    
    // Initialize SPIFFS
    if (!SPIFFS.begin(true)) {
        Serial.println("SPIFFS mount failed!");
        return;
    }
    
    // Connect to WiFi
    if (connectToWiFi("/wifi_config.json")) {
        Serial.println("WiFi connected successfully!");
        Serial.print("IP Address: ");
        Serial.println(WiFi.localIP());
    } else {
        Serial.println("WiFi connection failed!");
        // Handle failure (retry, enter AP mode, etc.)
    }
}

void loop() {
    // WiFi reconnection can be handled here if needed
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("WiFi disconnected, attempting reconnect...");
        connectToWiFi();
    }
    delay(1000);
}
```

## Error Handling

The library logs detailed messages to Serial:

| Log Message | Meaning |
|:------------|:--------|
| `Config file not found!` | JSON file doesn't exist in SPIFFS |
| `Failed to open file` | File exists but couldn't be opened |
| `Config file is empty!` | File exists but has no content |
| `JSON parse error` | Invalid JSON syntax |
| `SSID missing!` | No SSID field in configuration |
| `Failed to configure static IP` | Invalid IP configuration |
| `Invalid IP address format` | IP/gateway/subnet couldn't be parsed |
| `Missing static IP fields` | Static IP requested but fields missing |
| `Connected! IP: x.x.x.x` | Successful connection |
| `Connection failed.` | Timeout reached without connection |

## Notes

- WiFi mode is set to **Station (STA)** - the device connects to an existing network
- SPIFFS must be initialized before calling `connectToWiFi()`
- For production, consider implementing retry logic with exponential backoff
- The library does not implement WiFi Manager captive portal - for AP mode configuration, use a dedicated library

