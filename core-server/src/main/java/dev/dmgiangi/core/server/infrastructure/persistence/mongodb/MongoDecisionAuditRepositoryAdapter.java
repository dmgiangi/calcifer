package dev.dmgiangi.core.server.infrastructure.persistence.mongodb;

import dev.dmgiangi.core.server.domain.port.DecisionAuditRepository;
import dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.DecisionAuditEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * MongoDB adapter implementing DecisionAuditRepository port.
 */
@Repository
@RequiredArgsConstructor
public class MongoDecisionAuditRepositoryAdapter implements DecisionAuditRepository {

    private final SpringDataDecisionAuditRepository springDataRepository;

    @Override
    public void save(final AuditEntryData entry) {
        final var document = toDocument(entry);
        springDataRepository.save(document);
    }

    @Override
    public List<AuditEntryData> findByCorrelationId(final String correlationId) {
        return springDataRepository.findByCorrelationId(correlationId).stream()
                .map(this::toData)
                .toList();
    }

    @Override
    public List<AuditEntryData> findByDeviceIdAndTimeRange(final String deviceId, final Instant from, final Instant to) {
        return springDataRepository.findByDeviceIdAndTimestampBetween(deviceId, from, to).stream()
                .map(this::toData)
                .toList();
    }

    @Override
    public List<AuditEntryData> findBySystemIdAndTimeRange(final String systemId, final Instant from, final Instant to) {
        return springDataRepository.findBySystemIdAndTimestampBetween(systemId, from, to).stream()
                .map(this::toData)
                .toList();
    }

    @Override
    public List<AuditEntryData> findByDecisionTypeAndTimeRange(
            final DecisionType decisionType, final Instant from, final Instant to) {
        final var docType = toDocumentDecisionType(decisionType);
        return springDataRepository.findByDecisionTypeAndTimestampBetween(docType, from, to).stream()
                .map(this::toData)
                .toList();
    }

    private DecisionAuditEntry toDocument(final AuditEntryData data) {
        return new DecisionAuditEntry(
                data.id(),
                data.correlationId(),
                data.timestamp(),
                data.deviceId(),
                data.systemId(),
                toDocumentDecisionType(data.decisionType()),
                data.actor(),
                data.previousValue(),
                data.newValue(),
                data.reason(),
                data.context()
        );
    }

    private AuditEntryData toData(final DecisionAuditEntry doc) {
        return new AuditEntryData(
                doc.id(),
                doc.correlationId(),
                doc.timestamp(),
                doc.deviceId(),
                doc.systemId(),
                toPortDecisionType(doc.decisionType()),
                doc.actor(),
                doc.previousValue(),
                doc.newValue(),
                doc.reason(),
                doc.context()
        );
    }

    private DecisionAuditEntry.DecisionType toDocumentDecisionType(final DecisionType type) {
        return DecisionAuditEntry.DecisionType.valueOf(type.name());
    }

    private DecisionType toPortDecisionType(final DecisionAuditEntry.DecisionType type) {
        return DecisionType.valueOf(type.name());
    }
}

