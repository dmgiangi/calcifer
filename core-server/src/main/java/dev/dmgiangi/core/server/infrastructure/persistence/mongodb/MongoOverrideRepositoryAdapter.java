package dev.dmgiangi.core.server.infrastructure.persistence.mongodb;

import dev.dmgiangi.core.server.domain.port.OverrideRepository;
import dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.DeviceOverrideDocument;
import dev.dmgiangi.core.server.infrastructure.persistence.redis.RedisOverrideCacheAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB adapter implementing OverrideRepository port.
 * Uses write-through caching with Redis per Phase 0.1.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MongoOverrideRepositoryAdapter implements OverrideRepository {

    private final SpringDataOverrideRepository springDataRepository;
    private final RedisOverrideCacheAdapter redisCache;

    @Override
    public OverrideData save(final OverrideData override) {
        // Write-through: save to MongoDB first (source of truth)
        final var document = toDocument(override);
        final var saved = springDataRepository.save(document);
        final var result = toData(saved);

        // Then cache in Redis
        try {
            redisCache.cache(result);
        } catch (Exception e) {
            log.warn("Failed to cache override in Redis for target {}: {}", override.targetId(), e.getMessage());
            // Continue - MongoDB is source of truth
        }

        return result;
    }

    @Override
    public Optional<OverrideData> findByTargetAndCategory(final String targetId, final OverrideCategory category) {
        // Try cache first
        try {
            final var cached = redisCache.findByTargetAndCategory(targetId, category);
            if (cached.isPresent()) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("Failed to read override from Redis cache for target {}: {}", targetId, e.getMessage());
        }

        // Fall back to MongoDB
        final var docCategory = toDocumentCategory(category);
        return springDataRepository.findByTargetIdAndCategory(targetId, docCategory).map(this::toData);
    }

    @Override
    public List<OverrideData> findActiveByTarget(final String targetId) {
        // Try cache first
        try {
            final var cached = redisCache.findActiveByTarget(targetId);
            if (!cached.isEmpty()) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("Failed to read active overrides from Redis cache for target {}: {}", targetId, e.getMessage());
        }

        // Fall back to MongoDB
        return springDataRepository.findActiveByTargetId(targetId, Instant.now()).stream()
                .map(this::toData)
                .sorted(Comparator.comparing(OverrideData::category).reversed())
                .toList();
    }

    @Override
    public Optional<OverrideData> findEffectiveByTarget(final String targetId) {
        // Try cache first
        try {
            final var cached = redisCache.findEffectiveByTarget(targetId);
            if (cached.isPresent()) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("Failed to read effective override from Redis cache for target {}: {}", targetId, e.getMessage());
        }

        // Fall back to MongoDB
        final var activeOverrides = springDataRepository.findActiveByTargetId(targetId, Instant.now()).stream()
                .map(this::toData)
                .sorted(Comparator.comparing(OverrideData::category).reversed())
                .toList();
        return activeOverrides.isEmpty() ? Optional.empty() : Optional.of(activeOverrides.getFirst());
    }

    @Override
    public void deleteByTargetAndCategory(final String targetId, final OverrideCategory category) {
        // Delete from MongoDB first
        final var docCategory = toDocumentCategory(category);
        springDataRepository.deleteByTargetIdAndCategory(targetId, docCategory);

        // Then evict from cache
        try {
            redisCache.evict(targetId, category);
        } catch (Exception e) {
            log.warn("Failed to evict override from Redis cache for target {}: {}", targetId, e.getMessage());
        }
    }

    @Override
    public void deleteAllByTarget(final String targetId) {
        // Delete from MongoDB first
        springDataRepository.deleteAllByTargetId(targetId);

        // Then evict all from cache
        try {
            redisCache.evictAll(targetId);
        } catch (Exception e) {
            log.warn("Failed to evict all overrides from Redis cache for target {}: {}", targetId, e.getMessage());
        }
    }

    @Override
    public List<OverrideData> findExpired() {
        // Expired overrides are only queried from MongoDB (for cleanup)
        return springDataRepository.findExpired(Instant.now()).stream()
                .map(this::toData)
                .toList();
    }

    private DeviceOverrideDocument toDocument(final OverrideData data) {
        return new DeviceOverrideDocument(
                data.id(),
                data.targetId(),
                toDocumentScope(data.scope()),
                toDocumentCategory(data.category()),
                data.value(),
                data.reason(),
                data.expiresAt(),
                data.createdAt(),
                data.createdBy(),
                data.version()
        );
    }

    private OverrideData toData(final DeviceOverrideDocument doc) {
        return new OverrideData(
                doc.id(),
                doc.targetId(),
                toPortScope(doc.scope()),
                toPortCategory(doc.category()),
                doc.value(),
                doc.reason(),
                doc.expiresAt(),
                doc.createdAt(),
                doc.createdBy(),
                doc.version()
        );
    }

    private dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.OverrideCategory toDocumentCategory(
            final OverrideCategory category) {
        return dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.OverrideCategory.valueOf(category.name());
    }

    private OverrideCategory toPortCategory(
            final dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.OverrideCategory category) {
        return OverrideCategory.valueOf(category.name());
    }

    private dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.OverrideScope toDocumentScope(
            final OverrideScope scope) {
        return dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.OverrideScope.valueOf(scope.name());
    }

    private OverrideScope toPortScope(
            final dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.OverrideScope scope) {
        return OverrideScope.valueOf(scope.name());
    }
}

