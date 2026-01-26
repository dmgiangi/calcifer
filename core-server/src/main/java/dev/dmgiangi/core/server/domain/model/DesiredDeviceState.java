package dev.dmgiangi.core.server.domain.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Represents the desired state for a device, calculated from UserIntent, ReportedState and business rules.
 * This is the "what we want to command" in the Three-State Digital Twin pattern.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record DesiredDeviceState(
    DeviceId id,
    DeviceType type,
    DeviceValue value
) {
    public DesiredDeviceState {
        // Type-value consistency validation
        if (type == DeviceType.RELAY && !(value instanceof RelayValue)) {
            throw new IllegalArgumentException("Relay value must be RelayValue");
        }
        if (type == DeviceType.FAN && !(value instanceof FanValue)) {
            throw new IllegalArgumentException("Fan value must be FanValue");
        }
    }
}