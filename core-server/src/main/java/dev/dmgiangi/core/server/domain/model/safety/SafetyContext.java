package dev.dmgiangi.core.server.domain.model.safety;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Context object for safety rule evaluation.
 * Contains all information needed to evaluate safety rules for a proposed device state change.
 *
 * <p>The context provides:
 * <ul>
 *   <li>The device being changed (ID, type)</li>
 *   <li>Current device state (twin snapshot)</li>
 *   <li>Proposed new value</li>
 *   <li>Optional FunctionalSystem context (for cross-device rules)</li>
 *   <li>Related device states (for interlock rules)</li>
 * </ul>
 *
 * <p>This is an immutable value object - use the builder for construction.
 *
 * @param deviceId            the device being changed
 * @param deviceType          the type of device (RELAY, FAN, etc.)
 * @param currentSnapshot     the current state of the device (may be null for new devices)
 * @param proposedValue       the proposed new value
 * @param functionalSystem    the FunctionalSystem this device belongs to (optional)
 * @param relatedDeviceStates states of related devices in the same system (for interlock rules)
 * @param metadata            additional context data (e.g., temperature readings, timestamps)
 */
public record SafetyContext(
        DeviceId deviceId,
        DeviceType deviceType,
        DeviceTwinSnapshot currentSnapshot,
        DeviceValue proposedValue,
        FunctionalSystemData functionalSystem,
        Map<DeviceId, DeviceTwinSnapshot> relatedDeviceStates,
        Map<String, Object> metadata
) {
    public SafetyContext {
        Objects.requireNonNull(deviceId, "Device ID must not be null");
        Objects.requireNonNull(deviceType, "Device type must not be null");
        Objects.requireNonNull(proposedValue, "Proposed value must not be null");
        // currentSnapshot can be null for new devices
        // functionalSystem can be null for standalone devices
        relatedDeviceStates = relatedDeviceStates != null ? Map.copyOf(relatedDeviceStates) : Map.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Returns the current value of the device, if known.
     *
     * @return Optional containing the current value, or empty if unknown
     */
    public Optional<DeviceValue> getCurrentValue() {
        if (currentSnapshot == null || currentSnapshot.desired() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(currentSnapshot.desired().value());
    }

    /**
     * Returns the reported value of the device, if known.
     *
     * @return Optional containing the reported value, or empty if unknown
     */
    public Optional<DeviceValue> getReportedValue() {
        if (currentSnapshot == null || currentSnapshot.reported() == null) {
            return Optional.empty();
        }
        if (!currentSnapshot.reported().isKnown()) {
            return Optional.empty();
        }
        return Optional.ofNullable(currentSnapshot.reported().value());
    }

    /**
     * Checks if this device belongs to a FunctionalSystem.
     *
     * @return true if device is part of a system
     */
    public boolean hasSystem() {
        return functionalSystem != null;
    }

    /**
     * Returns the system type if device belongs to a system.
     *
     * @return Optional containing the system type (e.g., "TERMOCAMINO", "HVAC")
     */
    public Optional<String> getSystemType() {
        return Optional.ofNullable(functionalSystem).map(FunctionalSystemData::type);
    }

    /**
     * Gets a metadata value by key.
     *
     * @param key the metadata key
     * @param <T> the expected type
     * @return Optional containing the value, or empty if not present
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getMetadata(String key) {
        return Optional.ofNullable((T) metadata.get(key));
    }

    /**
     * Gets the state of a related device.
     *
     * @param deviceId the related device ID
     * @return Optional containing the device state, or empty if not available
     */
    public Optional<DeviceTwinSnapshot> getRelatedDeviceState(DeviceId deviceId) {
        return Optional.ofNullable(relatedDeviceStates.get(deviceId));
    }

    /**
     * Builder for SafetyContext.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing SafetyContext instances.
     */
    public static class Builder {
        private DeviceId deviceId;
        private DeviceType deviceType;
        private DeviceTwinSnapshot currentSnapshot;
        private DeviceValue proposedValue;
        private FunctionalSystemData functionalSystem;
        private Map<DeviceId, DeviceTwinSnapshot> relatedDeviceStates = Map.of();
        private Map<String, Object> metadata = Map.of();

        public Builder deviceId(DeviceId deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder deviceType(DeviceType deviceType) {
            this.deviceType = deviceType;
            return this;
        }

        public Builder currentSnapshot(DeviceTwinSnapshot currentSnapshot) {
            this.currentSnapshot = currentSnapshot;
            return this;
        }

        public Builder proposedValue(DeviceValue proposedValue) {
            this.proposedValue = proposedValue;
            return this;
        }

        public Builder functionalSystem(FunctionalSystemData functionalSystem) {
            this.functionalSystem = functionalSystem;
            return this;
        }

        public Builder relatedDeviceStates(Map<DeviceId, DeviceTwinSnapshot> relatedDeviceStates) {
            this.relatedDeviceStates = relatedDeviceStates;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public SafetyContext build() {
            return new SafetyContext(
                    deviceId, deviceType, currentSnapshot, proposedValue,
                    functionalSystem, relatedDeviceStates, metadata
            );
        }
    }
}

