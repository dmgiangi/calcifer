package dev.dmgiangi.core.server.domain.model;

public record DesiredDeviceState(
    DeviceId id,
    DeviceType type,
    Object value // Può essere Double, Boolean, Integer
) {
    public DesiredDeviceState {
        // Validazione di consistenza
        if (type == DeviceType.RELAY && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Relay value must be Boolean");
        }
        if (type == DeviceType.STEP_RELAY && !(value instanceof StepRelayState)) {
            throw new IllegalArgumentException("Step Relay value must be Integer");
        }
    }
}