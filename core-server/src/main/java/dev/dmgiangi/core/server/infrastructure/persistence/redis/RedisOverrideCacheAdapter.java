package dev.dmgiangi.core.server.infrastructure.persistence.redis;

import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Redis cache for active overrides per Phase 0.1.
 * Write-through pattern: MongoDB is source of truth, Redis is operational cache.
 *
 * <p>Key pattern: override:{targetId}:{category}
 * <p>Index pattern: override:index:{targetId} (sorted set by category priority)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOverrideCacheAdapter {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "override:";
    private static final String INDEX_PREFIX = "override:index:";

    /**
     * Caches an override (called after MongoDB save).
     */
    public void cache(final OverrideData override) {
        final var key = generateKey(override.targetId(), override.category());
        final var indexKey = generateIndexKey(override.targetId());

        // Store override data
        if (override.expiresAt() != null) {
            final var ttl = Duration.between(Instant.now(), override.expiresAt());
            if (ttl.isPositive()) {
                redisTemplate.opsForValue().set(key, override, ttl);
            }
        } else {
            redisTemplate.opsForValue().set(key, override);
        }

        // Add to index (score = category ordinal for priority sorting)
        redisTemplate.opsForZSet().add(indexKey, key, override.category().ordinal());

        log.debug("Cached override for target {} category {}", override.targetId(), override.category());
    }

    /**
     * Removes an override from cache (called after MongoDB delete).
     */
    public void evict(final String targetId, final OverrideCategory category) {
        final var key = generateKey(targetId, category);
        final var indexKey = generateIndexKey(targetId);

        redisTemplate.delete(key);
        redisTemplate.opsForZSet().remove(indexKey, key);

        log.debug("Evicted override cache for target {} category {}", targetId, category);
    }

    /**
     * Removes all overrides for a target from cache.
     */
    public void evictAll(final String targetId) {
        final var indexKey = generateIndexKey(targetId);
        final var keys = redisTemplate.opsForZSet().range(indexKey, 0, -1);

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys.stream().map(Object::toString).toList());
        }
        redisTemplate.delete(indexKey);

        log.debug("Evicted all override cache for target {}", targetId);
    }

    /**
     * Finds cached override by target and category.
     */
    public Optional<OverrideData> findByTargetAndCategory(final String targetId, final OverrideCategory category) {
        final var key = generateKey(targetId, category);
        final var value = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(value)
                .filter(OverrideData.class::isInstance)
                .map(OverrideData.class::cast)
                .filter(o -> !o.isExpired());
    }

    /**
     * Finds all active cached overrides for a target, ordered by priority (highest first).
     */
    public List<OverrideData> findActiveByTarget(final String targetId) {
        final var indexKey = generateIndexKey(targetId);
        final var keys = redisTemplate.opsForZSet().reverseRange(indexKey, 0, -1);

        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        return keys.stream()
                .map(Object::toString)
                .map(key -> redisTemplate.opsForValue().get(key))
                .filter(OverrideData.class::isInstance)
                .map(OverrideData.class::cast)
                .filter(o -> !o.isExpired())
                .sorted(Comparator.comparing(OverrideData::category).reversed())
                .toList();
    }

    /**
     * Finds the effective (highest priority) cached override for a target.
     */
    public Optional<OverrideData> findEffectiveByTarget(final String targetId) {
        final var activeOverrides = findActiveByTarget(targetId);
        return activeOverrides.isEmpty() ? Optional.empty() : Optional.of(activeOverrides.getFirst());
    }

    private String generateKey(final String targetId, final OverrideCategory category) {
        return KEY_PREFIX + targetId + ":" + category.name();
    }

    private String generateIndexKey(final String targetId) {
        return INDEX_PREFIX + targetId;
    }
}

