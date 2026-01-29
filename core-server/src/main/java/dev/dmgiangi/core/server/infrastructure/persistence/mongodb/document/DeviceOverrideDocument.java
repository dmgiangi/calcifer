package dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document;

import dev.dmgiangi.core.server.domain.model.DeviceValue;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB document for device/system overrides.
 * Per Phase 0.5: Categorized overrides with stacking semantics.
 *
 * <p>Key semantics:
 * <ul>
 *   <li>One override per (target, category) pair</li>
 *   <li>Higher category shadows lower</li>
 *   <li>On expiry, next category takes over</li>
 *   <li>HARDCODED_SAFETY/SYSTEM_SAFETY cannot be overridden (enforced by SafetyRuleEngine)</li>
 * </ul>
 */
@Document(collection = "device_overrides")
@CompoundIndex(name = "target_category_idx", def = "{'targetId': 1, 'category': 1}", unique = true)
public record DeviceOverrideDocument(
        @Id
        String id,

        /**
         * Target identifier: deviceId (format: "controllerId:componentId") or systemId
         */
        String targetId,

        /**
         * Scope: SYSTEM or DEVICE
         */
        OverrideScope scope,

        /**
         * Category: EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL
         */
        OverrideCategory category,

        /**
         * Override value (typed as DeviceValue for proper serialization)
         */
        DeviceValue value,

        /**
         * Human-readable reason for the override
         */
        String reason,

        /**
         * When the override expires (null = permanent until cancelled)
         */
        @Indexed(expireAfter = "0s")
        Instant expiresAt,

        /**
         * When the override was created
         */
        Instant createdAt,

        /**
         * Who created the override
         */
        String createdBy,

        /**
         * Optimistic locking version per 0.9
         */
        @Version
        Long version
) {
    /**
     * Factory method to create a new override.
     */
    public static DeviceOverrideDocument create(
            final String targetId,
            final OverrideScope scope,
            final OverrideCategory category,
            final DeviceValue value,
            final String reason,
            final Instant expiresAt,
            final String createdBy
    ) {
        final var id = generateId(targetId, category);
        return new DeviceOverrideDocument(
                id,
                targetId,
                scope,
                category,
                value,
                reason,
                expiresAt,
                Instant.now(),
                createdBy,
                null
        );
    }

    /**
     * Generates a deterministic ID based on target and category.
     * Ensures uniqueness per (target, category) pair.
     */
    public static String generateId(final String targetId, final OverrideCategory category) {
        return targetId + ":" + category.name();
    }

    /**
     * Returns true if this override has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Returns true if this override is permanent (no expiration).
     */
    public boolean isPermanent() {
        return expiresAt == null;
    }

    /**
     * Returns a copy with updated value and reason.
     */
    public DeviceOverrideDocument withValue(final DeviceValue newValue, final String newReason) {
        return new DeviceOverrideDocument(
                id, targetId, scope, category, newValue, newReason,
                expiresAt, createdAt, createdBy, version
        );
    }

    /**
     * Returns a copy with updated expiration.
     */
    public DeviceOverrideDocument withExpiration(final Instant newExpiresAt) {
        return new DeviceOverrideDocument(
                id, targetId, scope, category, value, reason,
                newExpiresAt, createdAt, createdBy, version
        );
    }
}

