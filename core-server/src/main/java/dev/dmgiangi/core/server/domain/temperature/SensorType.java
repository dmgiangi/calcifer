package dev.dmgiangi.core.server.domain.temperature;

public enum SensorType {
    ds18b20,
    thermocouple;

    public static SensorType fromString(String type) {
        return SensorType.valueOf(type.toLowerCase());
    }
}