package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.FunctionalSystemId;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;

import java.util.Optional;
import java.util.Set;

/**
 * Service for bidirectional lookup between devices and FunctionalSystems.
 * Per Phase 0.2: Exclusive membership - a device belongs to at most one system.
 *
 * <p>Provides:
 * <ul>
 *   <li>Device → System lookup (which system owns this device?)</li>
 *   <li>System → Devices lookup (which devices belong to this system?)</li>
 *   <li>Membership validation (is device in a system?)</li>
 * </ul>
 */
public interface DeviceSystemMappingService {

    /**
     * Finds the FunctionalSystem that contains a specific device.
     * Per Phase 0.2: Exclusive membership - device belongs to max one system.
     *
     * @param deviceId the device ID
     * @return Optional containing the system data if device is assigned
     */
    Optional<FunctionalSystemData> findSystemByDevice(DeviceId deviceId);

    /**
     * Finds the FunctionalSystem ID that contains a specific device.
     * Lightweight version that returns only the ID.
     *
     * @param deviceId the device ID
     * @return Optional containing the system ID if device is assigned
     */
    Optional<FunctionalSystemId> findSystemIdByDevice(DeviceId deviceId);

    /**
     * Finds all devices belonging to a FunctionalSystem.
     *
     * @param systemId the system ID
     * @return set of device IDs (empty if system not found or has no devices)
     */
    Set<DeviceId> findDevicesBySystem(FunctionalSystemId systemId);

    /**
     * Checks if a device belongs to any FunctionalSystem.
     *
     * @param deviceId the device ID
     * @return true if device is assigned to a system
     */
    boolean isDeviceInSystem(DeviceId deviceId);

    /**
     * Checks if a device belongs to a specific FunctionalSystem.
     *
     * @param deviceId the device ID
     * @param systemId the system ID
     * @return true if device belongs to the specified system
     */
    boolean isDeviceInSystem(DeviceId deviceId, FunctionalSystemId systemId);

    /**
     * Finds all devices that are related to a given device through the same system.
     * Useful for interlock rules that need to check related device states.
     *
     * @param deviceId the device ID
     * @return set of related device IDs (excluding the input device), empty if device is standalone
     */
    Set<DeviceId> findRelatedDevices(DeviceId deviceId);

    /**
     * Finds all standalone devices (not assigned to any system).
     * Useful for device management and auto-registration workflows.
     *
     * @param allKnownDeviceIds all known device IDs in the system
     * @return set of device IDs that are not assigned to any system
     */
    Set<DeviceId> findStandaloneDevices(Set<DeviceId> allKnownDeviceIds);

    /**
     * Validates that a device can be added to a system.
     * Per Phase 0.2: Exclusive membership - device cannot be in multiple systems.
     *
     * @param deviceId       the device ID to validate
     * @param targetSystemId the system to add the device to
     * @return validation result with reason if invalid
     */
    MembershipValidation validateMembership(DeviceId deviceId, FunctionalSystemId targetSystemId);

    /**
     * Result of membership validation.
     */
    record MembershipValidation(boolean isValid, String reason, FunctionalSystemId currentSystemId) {

        public static MembershipValidation validResult() {
            return new MembershipValidation(true, null, null);
        }

        public static MembershipValidation alreadyInTargetSystem() {
            return new MembershipValidation(true, "Device already in target system", null);
        }

        public static MembershipValidation inOtherSystem(FunctionalSystemId currentSystemId) {
            return new MembershipValidation(
                    false,
                    "Device already assigned to system " + currentSystemId,
                    currentSystemId
            );
        }

        public boolean isInOtherSystem() {
            return !isValid && currentSystemId != null;
        }
    }
}

