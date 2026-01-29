package dev.dmgiangi.core.server.infrastructure.rest.dto;

import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.infrastructure.rest.validation.ValidIntentRequest;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for submitting a user intent for a device.
 * Per Phase 0.12: Validated with @ValidIntentRequest for type-value consistency.
 *
 * @param type  the device type (RELAY or FAN)
 * @param value the desired value (Boolean for RELAY, Integer 0-4 for FAN)
 */
@ValidIntentRequest
public record IntentRequest(
        @NotNull(message = "Device type is required")
        DeviceType type,

        @NotNull(message = "Value is required")
        Object value
) {
}

