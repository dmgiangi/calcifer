package dev.dmgiangi.core.server.domain.port;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository port for FunctionalSystem aggregate.
 * Per Phase 0.2: FunctionalSystem owns configuration and device membership only.
 * Device states remain in Redis (via DeviceStateRepository).
 */
public interface FunctionalSystemRepository {

    /**
     * Saves a FunctionalSystem (create or update).
     * Uses optimistic locking via version field.
     *
     * @param system the system to save
     * @return the saved system with updated version
     * @throws org.springframework.dao.OptimisticLockingFailureException if version conflict
     */
    FunctionalSystemData save(FunctionalSystemData system);

    /**
     * Finds a FunctionalSystem by ID.
     *
     * @param id the system ID
     * @return Optional containing the system if found
     */
    Optional<FunctionalSystemData> findById(String id);

    /**
     * Finds all FunctionalSystems.
     *
     * @return list of all systems
     */
    List<FunctionalSystemData> findAll();

    /**
     * Finds the FunctionalSystem that contains a specific device.
     * Per 0.2: Exclusive membership - device belongs to max one system.
     *
     * @param deviceId the device ID (format: "controllerId:componentId")
     * @return Optional containing the system if device is assigned
     */
    Optional<FunctionalSystemData> findByDeviceId(String deviceId);

    /**
     * Deletes a FunctionalSystem by ID.
     *
     * @param id the system ID
     */
    void deleteById(String id);

    /**
     * Checks if a system exists by ID.
     *
     * @param id the system ID
     * @return true if exists
     */
    boolean existsById(String id);

    /**
     * Data transfer object for FunctionalSystem.
     * Decouples domain from infrastructure (MongoDB document).
     */
    record FunctionalSystemData(
            String id,
            String type,
            String name,
            java.util.Map<String, Object> configuration,
            Set<String> deviceIds,
            java.util.Map<String, Object> failSafeDefaults,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            String createdBy,
            Long version
    ) {
        /**
         * Factory method to create a new system with defaults.
         */
        public static FunctionalSystemData create(
                final String id,
                final String type,
                final String name,
                final java.util.Map<String, Object> configuration,
                final Set<String> deviceIds,
                final String createdBy
        ) {
            final var now = java.time.Instant.now();
            return new FunctionalSystemData(
                    id, type, name, configuration, deviceIds,
                    java.util.Map.of(), now, now, createdBy, null
            );
        }
    }
}

