package dev.dmgiangi.core.server.infrastructure.websocket.dto;

import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;

import java.time.Instant;
import java.util.List;

/**
 * WebSocket message for override notifications.
 * Per Phase 0.7: Real-time feedback for override events.
 *
 * @param targetId    the target identifier (device ID or system ID)
 * @param scope       whether this is a DEVICE or SYSTEM override
 * @param category    the override category (EMERGENCY, MAINTENANCE, SCHEDULED, MANUAL)
 * @param messageType the type of override event
 * @param value       the override value (may be null for cancelled/expired)
 * @param reason      human-readable reason for the override
 * @param warnings    any warnings generated during validation
 * @param timestamp   when this message was generated
 */
public record OverrideMessage(
        String targetId,
        Scope scope,
        OverrideCategory category,
        MessageType messageType,
        Object value,
        String reason,
        List<String> warnings,
        Instant timestamp
) {
    /**
     * Override scope.
     */
    public enum Scope {
        DEVICE,
        SYSTEM
    }

    /**
     * Override message types per Phase 0.7 decision.
     */
    public enum MessageType {
        /**
         * Override was applied successfully
         */
        OVERRIDE_APPLIED,
        /**
         * Override was blocked by higher priority or safety
         */
        OVERRIDE_BLOCKED,
        /**
         * Override was cancelled
         */
        OVERRIDE_CANCELLED,
        /**
         * Override expired (TTL reached)
         */
        OVERRIDE_EXPIRED,
        /**
         * Override was modified by safety rules
         */
        OVERRIDE_MODIFIED
    }

    /**
     * Creates a message for override applied.
     */
    public static OverrideMessage applied(
            final String targetId,
            final Scope scope,
            final OverrideCategory category,
            final Object value,
            final boolean wasModified,
            final List<String> warnings
    ) {
        final var messageType = wasModified ? MessageType.OVERRIDE_MODIFIED : MessageType.OVERRIDE_APPLIED;
        final var reason = wasModified
                ? "Override applied with modifications by safety rules"
                : "Override applied successfully";
        return new OverrideMessage(
                targetId, scope, category, messageType, value, reason, warnings, Instant.now()
        );
    }

    /**
     * Creates a message for override blocked.
     */
    public static OverrideMessage blocked(
            final String targetId,
            final Scope scope,
            final OverrideCategory category,
            final Object requestedValue,
            final String reason
    ) {
        return new OverrideMessage(
                targetId, scope, category, MessageType.OVERRIDE_BLOCKED,
                requestedValue, reason, List.of(), Instant.now()
        );
    }

    /**
     * Creates a message for override cancelled.
     */
    public static OverrideMessage cancelled(
            final String targetId,
            final Scope scope,
            final OverrideCategory category
    ) {
        return new OverrideMessage(
                targetId, scope, category, MessageType.OVERRIDE_CANCELLED,
                null, "Override cancelled", List.of(), Instant.now()
        );
    }

    /**
     * Creates a message for override expired.
     */
    public static OverrideMessage expired(
            final String targetId,
            final Scope scope,
            final OverrideCategory category
    ) {
        return new OverrideMessage(
                targetId, scope, category, MessageType.OVERRIDE_EXPIRED,
                null, "Override expired (TTL reached)", List.of(), Instant.now()
        );
    }
}

