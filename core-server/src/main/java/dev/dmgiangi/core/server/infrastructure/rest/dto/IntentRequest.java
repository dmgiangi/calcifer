package dev.dmgiangi.core.server.infrastructure.rest.dto;

import dev.dmgiangi.core.server.domain.model.DeviceType;

/**
 * DTO for submitting a user intent for a device.
 *
 * @param type  the device type (RELAY or FAN)
 * @param value the desired value (Boolean for RELAY, Integer 0-255 for FAN)
 */
public record IntentRequest(DeviceType type, Object value) {
}

