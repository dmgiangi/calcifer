package dev.dmgiangi.core.server.domain.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents the actual state reported by a device via MQTT feedback.
 * This is the "what the device says it is" in the Three-State Digital Twin pattern.
 *
 * <p>When {@code isKnown} is false, the device state is unknown (e.g., device just booted,
 * never reported, or went offline). In this case, {@code value} will be null.</p>
 *
 * @param id         the device identifier
 * @param type       the device type (RELAY or FAN)
 * @param value      the reported value (RelayValue for RELAY, FanValue for FAN), null if unknown
 * @param reportedAt the timestamp when the state was last reported
 * @param isKnown    whether the device state is known (false for cold boot or offline devices)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record ReportedDeviceState(
    DeviceId id,
    DeviceType type,
    DeviceValue value,
    Instant reportedAt,
    boolean isKnown
) {
    public ReportedDeviceState {
        Objects.requireNonNull(id, "Device id must not be null");
        Objects.requireNonNull(type, "Device type must not be null");
        Objects.requireNonNull(reportedAt, "ReportedAt must not be null");

        // When state is known, value must be present and type-consistent
        if (isKnown) {
            Objects.requireNonNull(value, "Value must not be null when state is known");

            if (type == DeviceType.RELAY && !(value instanceof RelayValue)) {
                throw new IllegalArgumentException("Relay value must be RelayValue");
            }
            if (type == DeviceType.FAN && !(value instanceof FanValue)) {
                throw new IllegalArgumentException("Fan value must be FanValue");
            }
        } else {
            // When state is unknown, value should be null
            if (value != null) {
                throw new IllegalArgumentException("Value must be null when state is unknown");
            }
        }
    }

    /**
     * Factory method to create an unknown state (e.g., for device cold boot).
     *
     * @param id   the device identifier
     * @param type the device type
     * @return a ReportedDeviceState representing unknown state
     */
    public static ReportedDeviceState unknown(DeviceId id, DeviceType type) {
        return new ReportedDeviceState(id, type, null, Instant.now(), false);
    }

    /**
     * Factory method to create a known state with current timestamp.
     *
     * @param id    the device identifier
     * @param type  the device type
     * @param value the reported value
     * @return a ReportedDeviceState with known state
     */
    public static ReportedDeviceState known(DeviceId id, DeviceType type, DeviceValue value) {
        return new ReportedDeviceState(id, type, value, Instant.now(), true);
    }
}

