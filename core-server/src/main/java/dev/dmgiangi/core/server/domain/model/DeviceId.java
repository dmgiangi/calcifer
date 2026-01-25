package dev.dmgiangi.core.server.domain.model;

import org.springframework.util.Assert;


public record DeviceId(
    String controllerId,
    String componentId
) {

    public DeviceId {
        Assert.hasText(controllerId, "Controller ID cannot be empty");
        Assert.hasText(componentId, "Component ID cannot be empty");
    }


    @Override
    public String toString() {
        return controllerId + ":" + componentId;
    }

    public static DeviceId fromString(String source) {
        String[] parts = source.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid ID format");
        }
        return new DeviceId(parts[0], parts[1]);
    }
}