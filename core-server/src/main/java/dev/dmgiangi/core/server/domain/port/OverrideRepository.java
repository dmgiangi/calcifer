package dev.dmgiangi.core.server.domain.port;

import dev.dmgiangi.core.server.domain.model.DeviceValue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository port for device/system overrides.
 * Per Phase 0.5: Categorized overrides with stacking semantics.
 */
public interface OverrideRepository {

    /**
     * Saves an override (create or update).
     * Per 0.5: One override per (target, category) pair.
     *
     * @param override the override to save
     * @return the saved override with updated version
     * @throws org.springframework.dao.OptimisticLockingFailureException if version conflict
     */
    OverrideData save(OverrideData override);

    /**
     * Finds an override by target and category.
     *
     * @param targetId the target ID (deviceId or systemId)
     * @param category the override category
     * @return Optional containing the override if found
     */
    Optional<OverrideData> findByTargetAndCategory(String targetId, OverrideCategory category);

    /**
     * Finds all active overrides for a target (not expired).
     * Ordered by category priority (highest first).
     *
     * @param targetId the target ID
     * @return list of active overrides
     */
    List<OverrideData> findActiveByTarget(String targetId);

    /**
     * Finds the effective override for a target (highest priority active override).
     *
     * @param targetId the target ID
     * @return Optional containing the effective override
     */
    Optional<OverrideData> findEffectiveByTarget(String targetId);

    /**
     * Deletes an override by target and category.
     *
     * @param targetId the target ID
     * @param category the override category
     */
    void deleteByTargetAndCategory(String targetId, OverrideCategory category);

    /**
     * Deletes all overrides for a target.
     *
     * @param targetId the target ID
     */
    void deleteAllByTarget(String targetId);

    /**
     * Finds all expired overrides (for cleanup).
     *
     * @return list of expired overrides
     */
    List<OverrideData> findExpired();

    /**
     * Override category with fixed precedence per Phase 0.5.
     */
    enum OverrideCategory {
        MANUAL,
        SCHEDULED,
        MAINTENANCE,
        EMERGENCY;

        public boolean hasHigherPriorityThan(final OverrideCategory other) {
            return this.ordinal() > other.ordinal();
        }
    }

    /**
     * Override scope per Phase 0.5.
     */
    enum OverrideScope {
        SYSTEM,
        DEVICE
    }

    /**
     * Data transfer object for Override.
     * The value field is typed as DeviceValue to ensure proper Jackson serialization
     * with type information for Redis caching.
     */
    record OverrideData(
            String id,
            String targetId,
            OverrideScope scope,
            OverrideCategory category,
            DeviceValue value,
            String reason,
            Instant expiresAt,
            Instant createdAt,
            String createdBy,
            Long version
    ) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }

        public boolean isPermanent() {
            return expiresAt == null;
        }

        public static OverrideData create(
                final String targetId,
                final OverrideScope scope,
                final OverrideCategory category,
                final DeviceValue value,
                final String reason,
                final Instant expiresAt,
                final String createdBy
        ) {
            final var id = targetId + ":" + category.name();
            return new OverrideData(
                    id, targetId, scope, category, value, reason,
                    expiresAt, Instant.now(), createdBy, null
            );
        }
    }
}

