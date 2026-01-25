package dev.dmgiangi.core.server.domain.temperature;

public record Temperature(
    String client,
    SensorType type,
    String sensorName,
    boolean isError,
    double value
) {}
