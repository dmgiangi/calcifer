package dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * MongoDB document for decision audit trail per Phase 0.10.
 * Queryable for compliance and debugging.
 *
 * <p>Records all significant decisions made by the system including:
 * <ul>
 *   <li>Intent received/rejected/modified</li>
 *   <li>Desired state calculated</li>
 *   <li>Override applied/blocked/expired</li>
 *   <li>Safety rule activation</li>
 *   <li>Convergence events</li>
 * </ul>
 */
@Document(collection = "decision_audit")
public record DecisionAuditEntry(
        @Id
        String id,

        /**
         * Correlation ID for distributed tracing (from Micrometer/OpenTelemetry)
         */
        @Indexed
        String correlationId,

        /**
         * When the decision was made
         */
        @Indexed
        Instant timestamp,

        /**
         * Device ID if applicable (format: "controllerId:componentId")
         */
        @Indexed
        String deviceId,

        /**
         * System ID if applicable
         */
        @Indexed
        String systemId,

        /**
         * Type of decision made
         */
        @Indexed
        DecisionType decisionType,

        /**
         * Who/what initiated the decision (user, system, scheduler, etc.)
         */
        String actor,

        /**
         * Previous value before the decision (null if N/A)
         */
        Object previousValue,

        /**
         * New value after the decision (null if rejected)
         */
        Object newValue,

        /**
         * Human-readable reason for the decision
         */
        String reason,

        /**
         * Additional context (e.g., rule that triggered, override category, etc.)
         */
        Map<String, Object> context
) {
    /**
     * Factory method to create a new audit entry with auto-generated ID.
     */
    public static DecisionAuditEntry create(
            final String correlationId,
            final String deviceId,
            final String systemId,
            final DecisionType decisionType,
            final String actor,
            final Object previousValue,
            final Object newValue,
            final String reason,
            final Map<String, Object> context
    ) {
        return new DecisionAuditEntry(
                UUID.randomUUID().toString(),
                correlationId,
                Instant.now(),
                deviceId,
                systemId,
                decisionType,
                actor,
                previousValue,
                newValue,
                reason,
                context != null ? context : Map.of()
        );
    }

    /**
     * Types of decisions that can be audited.
     */
    public enum DecisionType {
        INTENT_RECEIVED,
        INTENT_REJECTED,
        INTENT_MODIFIED,
        DESIRED_CALCULATED,
        OVERRIDE_APPLIED,
        OVERRIDE_BLOCKED,
        OVERRIDE_EXPIRED,
        SAFETY_RULE_ACTIVATED,
        DEVICE_CONVERGED,
        DEVICE_DIVERGED,
        CIRCUIT_BREAKER_OPENED,
        CIRCUIT_BREAKER_CLOSED,
        FALLBACK_ACTIVATED,
        FAIL_SAFE_APPLIED
    }
}

