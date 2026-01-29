package dev.dmgiangi.core.server.domain.exception;

import dev.dmgiangi.core.server.domain.model.DeviceId;

/**
 * Exception thrown when a device is not found.
 */
public class DeviceNotFoundException extends RuntimeException {

    private final DeviceId deviceId;

    public DeviceNotFoundException(final DeviceId deviceId) {
        super("Device not found: " + deviceId);
        this.deviceId = deviceId;
    }

    public DeviceNotFoundException(final String controllerId, final String componentId) {
        this(new DeviceId(controllerId, componentId));
    }

    public DeviceId getDeviceId() {
        return deviceId;
    }
}

