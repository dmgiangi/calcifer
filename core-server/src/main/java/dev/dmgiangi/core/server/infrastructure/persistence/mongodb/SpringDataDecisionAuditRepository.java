package dev.dmgiangi.core.server.infrastructure.persistence.mongodb;

import dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.DecisionAuditEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data MongoDB repository for DecisionAuditEntry documents.
 */
public interface SpringDataDecisionAuditRepository extends MongoRepository<DecisionAuditEntry, String> {

    /**
     * Finds audit entries by correlation ID.
     */
    List<DecisionAuditEntry> findByCorrelationId(String correlationId);

    /**
     * Finds audit entries by device ID within a time range.
     */
    List<DecisionAuditEntry> findByDeviceIdAndTimestampBetween(String deviceId, Instant from, Instant to);

    /**
     * Finds audit entries by system ID within a time range.
     */
    List<DecisionAuditEntry> findBySystemIdAndTimestampBetween(String systemId, Instant from, Instant to);

    /**
     * Finds audit entries by decision type within a time range.
     */
    List<DecisionAuditEntry> findByDecisionTypeAndTimestampBetween(
            DecisionAuditEntry.DecisionType decisionType, Instant from, Instant to);
}

