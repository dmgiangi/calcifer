package dev.dmgiangi.core.server.infrastructure.messaging.outbound.event;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.StepRelayState;


public record DeviceCommandEvent(
    DeviceId deviceId,
    DeviceType type,
    Object value
) {
    public DeviceCommandEvent {
        if (type == DeviceType.RELAY) {
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException("Value must be Boolean for RELAY type");
            }
        } else if (type == DeviceType.STEP_RELAY) {
            if (!(value instanceof StepRelayState)) {
                throw new IllegalArgumentException("Value must be StepRelayState for STEP_RELAY type");
            }
        } else {
            throw new IllegalArgumentException("Unsupported device type: " + type);
        }
    }
}
