package dev.dmgiangi.core.server.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository port for decision audit trail per Phase 0.10.
 * Write-heavy, read for compliance and debugging.
 */
public interface DecisionAuditRepository {

    /**
     * Records a decision audit entry.
     *
     * @param entry the audit entry to save
     */
    void save(AuditEntryData entry);

    /**
     * Finds audit entries by correlation ID.
     *
     * @param correlationId the correlation ID from distributed tracing
     * @return list of audit entries
     */
    List<AuditEntryData> findByCorrelationId(String correlationId);

    /**
     * Finds audit entries by device ID within a time range.
     *
     * @param deviceId the device ID
     * @param from     start of time range (inclusive)
     * @param to       end of time range (exclusive)
     * @return list of audit entries
     */
    List<AuditEntryData> findByDeviceIdAndTimeRange(String deviceId, Instant from, Instant to);

    /**
     * Finds audit entries by system ID within a time range.
     *
     * @param systemId the system ID
     * @param from     start of time range (inclusive)
     * @param to       end of time range (exclusive)
     * @return list of audit entries
     */
    List<AuditEntryData> findBySystemIdAndTimeRange(String systemId, Instant from, Instant to);

    /**
     * Finds audit entries by decision type within a time range.
     *
     * @param decisionType the decision type
     * @param from         start of time range (inclusive)
     * @param to           end of time range (exclusive)
     * @return list of audit entries
     */
    List<AuditEntryData> findByDecisionTypeAndTimeRange(DecisionType decisionType, Instant from, Instant to);

    /**
     * Types of decisions that can be audited.
     */
    enum DecisionType {
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

    /**
     * Data transfer object for audit entry.
     */
    record AuditEntryData(
            String id,
            String correlationId,
            Instant timestamp,
            String deviceId,
            String systemId,
            DecisionType decisionType,
            String actor,
            Object previousValue,
            Object newValue,
            String reason,
            Map<String, Object> context
    ) {
        public static AuditEntryData create(
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
            return new AuditEntryData(
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
    }
}

