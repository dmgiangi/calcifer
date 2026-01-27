package dev.dmgiangi.core.server.infrastructure.messaging.outbound.event;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;


/**
 * Event representing a command to be sent to a device via MQTT.
 *
 * @param deviceId the target device identifier
 * @param type the device type (RELAY or FAN)
 * @param value the command value: Boolean for RELAY, Integer (0-100) for FAN
 */
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
        } else if (type == DeviceType.FAN) {
            if (!(value instanceof Integer speed)) {
                throw new IllegalArgumentException("Value must be Integer for FAN type");
            }
            if (speed < 0 || speed > 100) {
                throw new IllegalArgumentException("FAN speed must be between 0 and 100");
            }
        } else {
            throw new IllegalArgumentException("Unsupported device type: " + type);
        }
    }
}
