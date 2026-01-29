package dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * MongoDB document for FunctionalSystem aggregate.
 * Per Phase 0.2: Lightweight Aggregate - owns CONFIGURATION and DEVICE MEMBERSHIP only.
 * Device states remain in Redis.
 *
 * <p>Supports:
 * <ul>
 *   <li>Device auto-registration (devices can exist standalone, be added later)</li>
 *   <li>Exclusive membership (device belongs to max one system)</li>
 *   <li>Optimistic locking via @Version</li>
 * </ul>
 */
@Document(collection = "functional_systems")
public record FunctionalSystemDocument(
        @Id
        String id,

        /**
         * System type: TERMOCAMINO, HVAC, IRRIGATION, GENERIC
         */
        String type,

        /**
         * Human-readable name for the system
         */
        String name,

        /**
         * System configuration: mode, targetTemp, schedule, safetyThresholds
         */
        Map<String, Object> configuration,

        /**
         * Device IDs belonging to this system (format: "controllerId:componentId")
         */
        Set<String> deviceIds,

        /**
         * Fail-safe defaults per 0.3: applied when fallbacks fail
         */
        Map<String, Object> failSafeDefaults,

        /**
         * When the system was created
         */
        Instant createdAt,

        /**
         * When the system was last updated
         */
        Instant updatedAt,

        /**
         * Who created/owns this system
         */
        String createdBy,

        /**
         * Optimistic locking version per 0.9
         */
        @Version
        Long version
) {
    /**
     * Factory method to create a new FunctionalSystem with defaults.
     */
    public static FunctionalSystemDocument create(
            final String id,
            final String type,
            final String name,
            final Map<String, Object> configuration,
            final Set<String> deviceIds,
            final String createdBy
    ) {
        final var now = Instant.now();
        return new FunctionalSystemDocument(
                id,
                type,
                name,
                configuration,
                deviceIds,
                Map.of(),
                now,
                now,
                createdBy,
                null
        );
    }

    /**
     * Returns a copy with updated configuration.
     */
    public FunctionalSystemDocument withConfiguration(final Map<String, Object> newConfiguration) {
        return new FunctionalSystemDocument(
                id, type, name, newConfiguration, deviceIds, failSafeDefaults,
                createdAt, Instant.now(), createdBy, version
        );
    }

    /**
     * Returns a copy with a device added.
     */
    public FunctionalSystemDocument withDeviceAdded(final String deviceId) {
        final var newDeviceIds = new java.util.HashSet<>(deviceIds);
        newDeviceIds.add(deviceId);
        return new FunctionalSystemDocument(
                id, type, name, configuration, Set.copyOf(newDeviceIds), failSafeDefaults,
                createdAt, Instant.now(), createdBy, version
        );
    }

    /**
     * Returns a copy with a device removed.
     */
    public FunctionalSystemDocument withDeviceRemoved(final String deviceId) {
        final var newDeviceIds = new java.util.HashSet<>(deviceIds);
        newDeviceIds.remove(deviceId);
        return new FunctionalSystemDocument(
                id, type, name, configuration, Set.copyOf(newDeviceIds), failSafeDefaults,
                createdAt, Instant.now(), createdBy, version
        );
    }
}

