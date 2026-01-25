package dev.dmgiangi.core.server.domain.model;

public enum DeviceType {
    TEMPERATURE_SENSOR(DeviceCapability.INPUT), RELAY(DeviceCapability.OUTPUT), STEP_RELAY(DeviceCapability.OUTPUT);

    public final DeviceCapability capability;

    DeviceType(DeviceCapability capability) {
        this.capability = capability;
    }
}

