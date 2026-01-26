package dev.dmgiangi.core.server.domain.model;

import org.springframework.util.Assert;

import java.time.Instant;

/**
 * Represents raw actuator feedback received from MQTT.
 * Contains the raw payload string which will be parsed later
 * by the ActuatorFeedbackProcessor into the appropriate DeviceValue.
 *
 * @param id          The device identifier (controllerId + componentId)
 * @param type        The type of device (FAN or RELAY)
 * @param rawValue    The raw MQTT payload string
 * @param receivedAt  The timestamp when the feedback was received
 */
public record ActuatorFeedback(
    DeviceId id,
    DeviceType type,
    String rawValue,
    Instant receivedAt
) {

    public ActuatorFeedback {
        Assert.notNull(id, "Device ID cannot be null");
        Assert.notNull(type, "Device type cannot be null");
        Assert.hasText(rawValue, "Raw value cannot be empty");
        Assert.notNull(receivedAt, "Received timestamp cannot be null");
    }
}

