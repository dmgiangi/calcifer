package dev.dmgiangi.core.server.domain.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents the user's intent for a device state.
 * This is the "what the user wants" in the Three-State Digital Twin pattern.
 *
 * @param id          the device identifier
 * @param type        the device type (RELAY or FAN)
 * @param value       the desired value (RelayValue for RELAY, FanValue for FAN)
 * @param requestedAt the timestamp when the intent was requested
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record UserIntent(
    DeviceId id,
    DeviceType type,
    DeviceValue value,
    Instant requestedAt
) {
    public UserIntent {
        Objects.requireNonNull(id, "Device id must not be null");
        Objects.requireNonNull(type, "Device type must not be null");
        Objects.requireNonNull(value, "Value must not be null");
        Objects.requireNonNull(requestedAt, "RequestedAt must not be null");

        // Type-value consistency validation
        if (type == DeviceType.RELAY && !(value instanceof RelayValue)) {
            throw new IllegalArgumentException("Relay value must be RelayValue");
        }
        if (type == DeviceType.FAN && !(value instanceof FanValue)) {
            throw new IllegalArgumentException("Fan value must be FanValue");
        }
    }

    /**
     * Factory method to create a UserIntent with current timestamp.
     *
     * @param id    the device identifier
     * @param type  the device type
     * @param value the desired value
     * @return a new UserIntent with current timestamp
     */
    public static UserIntent now(DeviceId id, DeviceType type, DeviceValue value) {
        return new UserIntent(id, type, value, Instant.now());
    }
}

