package dev.dmgiangi.core.server.infrastructure.persistence.mongodb;

import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.FunctionalSystemDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB adapter implementing FunctionalSystemRepository port.
 */
@Repository
@RequiredArgsConstructor
public class MongoFunctionalSystemRepositoryAdapter implements FunctionalSystemRepository {

    private final SpringDataFunctionalSystemRepository springDataRepository;

    @Override
    public FunctionalSystemData save(final FunctionalSystemData system) {
        final var document = toDocument(system);
        final var saved = springDataRepository.save(document);
        return toData(saved);
    }

    @Override
    public Optional<FunctionalSystemData> findById(final String id) {
        return springDataRepository.findById(id).map(this::toData);
    }

    @Override
    public List<FunctionalSystemData> findAll() {
        return springDataRepository.findAll().stream()
                .map(this::toData)
                .toList();
    }

    @Override
    public Optional<FunctionalSystemData> findByDeviceId(final String deviceId) {
        return springDataRepository.findByDeviceIdsContaining(deviceId).map(this::toData);
    }

    @Override
    public void deleteById(final String id) {
        springDataRepository.deleteById(id);
    }

    @Override
    public boolean existsById(final String id) {
        return springDataRepository.existsById(id);
    }

    private FunctionalSystemDocument toDocument(final FunctionalSystemData data) {
        return new FunctionalSystemDocument(
                data.id(),
                data.type(),
                data.name(),
                data.configuration(),
                data.deviceIds(),
                data.failSafeDefaults(),
                data.createdAt(),
                data.updatedAt(),
                data.createdBy(),
                data.version()
        );
    }

    private FunctionalSystemData toData(final FunctionalSystemDocument doc) {
        return new FunctionalSystemData(
                doc.id(),
                doc.type(),
                doc.name(),
                doc.configuration(),
                doc.deviceIds(),
                doc.failSafeDefaults(),
                doc.createdAt(),
                doc.updatedAt(),
                doc.createdBy(),
                doc.version()
        );
    }
}

