#!/usr/bin/env python3
"""
MQTT Test Script for ESP32 IoT Device

Publishes test signals to actuator topics based on pin_config.json.
Subscribes to sensor topics to display incoming data.
Automatically resends configured values for actuators every (pollingInterval - 1s).

Usage:
    python mqtt_test.py [--host HOST] [--port PORT]

Requirements:
    pip install paho-mqtt
"""

import argparse
import json
import sys
import threading
import time

import paho.mqtt.client as mqtt

# Thread-safe storage for actuator values
# Key: topic, Value: string value to publish
actuator_values = {}
actuator_lock = threading.Lock()

# Load configurations
PIN_CONFIG_PATH = "../iot-device/data/pin_config.json"
MQTT_CONFIG_PATH = "../iot-device/data/mqtt_config.json"


def load_configs():
    """Load pin and MQTT configurations from JSON files."""
    try:
        with open(PIN_CONFIG_PATH) as f:
            pin_config = json.load(f)
        with open(MQTT_CONFIG_PATH) as f:
            mqtt_config = json.load(f)
        return pin_config, mqtt_config
    except FileNotFoundError as e:
        print(f"❌ Config file not found: {e}")
        sys.exit(1)


def get_topic_prefix(mode: str) -> str:
    """Map pin mode to MQTT topic prefix."""
    mode_map = {
        "DS18B20": "ds18b20",
        "THERMOCOUPLE": "thermocouple",
        "FAN": "fan",
        "OUTPUT_DIGITAL": "digital_output",
        "PWM": "pwm",
        "INPUT_DIGITAL": "digital_input",
        "INPUT_ANALOG": "analog_input",
        "OUTPUT_ANALOG": "analog_output",
        "DHT22_SENSOR": "dht22",
        "YL_69_SENSOR": "yl69",
    }
    return mode_map.get(mode, mode.lower())


def on_connect(client, userdata, flags, rc):
    """Callback when connected to MQTT broker."""
    if rc == 0:
        print(f"✅ Connected to MQTT broker")
        # Subscribe to all sensor topics
        client_id = userdata["client_id"]
        for pin in userdata["pins"]:
            mode = pin["mode"]
            name = pin["name"]
            prefix = get_topic_prefix(mode)

            if mode in ["DS18B20", "THERMOCOUPLE"]:
                topic = f"/{client_id}/{prefix}/{name}/value"
                client.subscribe(topic)
                print(f"   📥 Subscribed: {topic}")
            elif mode == "FAN":
                topic = f"/{client_id}/{prefix}/{name}/state"
                client.subscribe(topic)
                print(f"   📥 Subscribed: {topic}")
            elif mode == "OUTPUT_DIGITAL":
                topic = f"/{client_id}/{prefix}/{name}/state"
                client.subscribe(topic)
                print(f"   📥 Subscribed: {topic}")
    else:
        print(f"❌ Connection failed with code {rc}")


def on_message(client, userdata, msg):
    """Callback when message received."""
    print(f"   📨 {msg.topic}: {msg.payload.decode()}")


def auto_publisher(client, client_id, pin_config, stop_event):
    """Background thread to auto-publish actuator values.

    Reads current values from the thread-safe actuator_values dict,
    which can be updated by manual commands from the main thread.
    """
    global actuator_values, actuator_lock

    actuators = []
    for pin in pin_config:
        mode = pin["mode"]
        if mode in ["FAN", "OUTPUT_DIGITAL"]:
            name = pin["name"]
            prefix = get_topic_prefix(mode)
            topic = f"/{client_id}/{prefix}/{name}/set"

            # Determine default value
            default_val = pin.get("defaultState", 0)

            # Initialize shared actuator value
            with actuator_lock:
                actuator_values[topic] = str(default_val)

            # Determine interval (default 30s if missing)
            interval_ms = pin.get("pollingInterval", 30000)
            interval_sec = max(1, (interval_ms / 1000.0) - 1.0)  # -1 second as requested

            actuators.append({
                "topic": topic,
                "interval": interval_sec,
                "last_sent": 0
            })

    print(f"   🔄 Auto-publisher started for {len(actuators)} actuators")

    while not stop_event.is_set():
        now = time.time()
        for act in actuators:
            if now - act["last_sent"] >= act["interval"]:
                # Read current value from shared storage (thread-safe)
                with actuator_lock:
                    value = actuator_values.get(act["topic"], "0")
                client.publish(act["topic"], value)
                # print(f"   🔄 Auto-published: {act['topic']} = {value}")
                act["last_sent"] = now

        time.sleep(0.1)


def main():
    parser = argparse.ArgumentParser(description="MQTT Test Script for ESP32 IoT Device")
    parser.add_argument("--host", help="MQTT broker host (overrides config)")
    parser.add_argument("--port", type=int, help="MQTT broker port (overrides config)")
    args = parser.parse_args()

    # Load configurations
    pin_config, mqtt_config = load_configs()

    host = args.host or mqtt_config["host"]
    port = args.port or mqtt_config.get("port", 1883)
    client_id = mqtt_config["clientId"]
    username = mqtt_config.get("username", "")
    password = mqtt_config.get("password", "")

    print(f"🔧 MQTT Test Script")
    print(f"   Broker: {host}:{port}")
    print(f"   Client ID: {client_id}")
    print()

    # Setup MQTT client
    client = mqtt.Client(client_id=f"{client_id}_test")
    client.user_data_set({"client_id": client_id, "pins": pin_config})
    client.on_connect = on_connect
    client.on_message = on_message

    if username:
        client.username_pw_set(username, password)

    # Connect
    try:
        client.connect(host, port, 60)
    except Exception as e:
        print(f"❌ Failed to connect: {e}")
        sys.exit(1)

    client.loop_start()
    time.sleep(1)  # Wait for connection

    # Start auto-publisher thread
    stop_event = threading.Event()
    pub_thread = threading.Thread(target=auto_publisher, args=(client, client_id, pin_config, stop_event))
    pub_thread.daemon = True
    pub_thread.start()

    # Build actuator topics for manual control
    actuators_manual = []
    for pin in pin_config:
        mode = pin["mode"]
        name = pin["name"]
        prefix = get_topic_prefix(mode)

        if mode == "FAN":
            actuators_manual.append({"name": name, "topic": f"/{client_id}/{prefix}/{name}/set", "range": "0-4"})
        elif mode == "OUTPUT_DIGITAL":
            actuators_manual.append({"name": name, "topic": f"/{client_id}/{prefix}/{name}/set", "range": "0-1"})

    # Interactive menu
    print("\n📤 Available actuators (Manual Override):")
    for i, act in enumerate(actuators_manual):
        print(f"   [{i}] {act['name']} ({act['range']}) -> {act['topic']}")

    print("\nCommands:")
    print("   <index> <value>  - Send value to actuator (e.g., '0 50')")
    print("   q                - Quit")
    print("\n   💡 Manual values persist and are auto-republished before watchdog timeout")
    print()

    try:
        while True:
            cmd = input(">>> ").strip()
            if cmd.lower() == "q":
                break

            parts = cmd.split()
            if len(parts) == 2:
                try:
                    idx = int(parts[0])
                    value = parts[1]
                    if 0 <= idx < len(actuators_manual):
                        topic = actuators_manual[idx]["topic"]
                        # Update shared actuator value (thread-safe)
                        with actuator_lock:
                            actuator_values[topic] = value
                        client.publish(topic, value)
                        print(f"   📤 Manual Publish: {topic} = {value} (will auto-republish)")
                    else:
                        print("   ⚠️  Invalid index")
                except ValueError:
                    print("   ⚠️  Usage: <index> <value>")
            else:
                pass  # Ignore empty or malformed input to keep loop clean
    except KeyboardInterrupt:
        print("\n")

    print("Stopping...")
    stop_event.set()
    pub_thread.join()
    client.loop_stop()
    client.disconnect()
    print("👋 Disconnected")


if __name__ == "__main__":
    main()
