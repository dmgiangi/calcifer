package dev.dmgiangi.core.server.infrastructure.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Service for ensuring idempotent message processing.
 *
 * <p>Per Phase 0.6/0.18 decisions:
 * <ul>
 *   <li>Uses Redis SETNX with 5-minute TTL for deduplication</li>
 *   <li>Key pattern: idempotency:{messageId}</li>
 *   <li>Shared across instances for distributed deduplication</li>
 * </ul>
 *
 * <p>Usage: Call {@link #tryAcquire(String)} before processing a message.
 * Returns true if this is the first time seeing this key (proceed with processing),
 * false if duplicate (skip processing).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final String MARKER_VALUE = "1";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Attempts to acquire an idempotency lock for the given key.
     *
     * @param idempotencyKey the unique key for this message (messageId or content hash)
     * @return true if this is the first time seeing this key (proceed with processing),
     * false if duplicate (skip processing)
     */
    public boolean tryAcquire(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("Null or blank idempotency key provided, allowing processing");
            return true;
        }

        final var redisKey = KEY_PREFIX + idempotencyKey;

        try {
            // SETNX with TTL - returns true if key was set (first time), false if already exists
            final var result = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, MARKER_VALUE, DEFAULT_TTL);

            final var isFirstTime = Boolean.TRUE.equals(result);

            if (!isFirstTime) {
                log.debug("Duplicate message detected, idempotency key: {}", idempotencyKey);
            }

            return isFirstTime;
        } catch (Exception e) {
            // On Redis failure, allow processing (fail-open for availability)
            // The worst case is duplicate processing, which is safer than dropping messages
            log.warn("Redis error during idempotency check for key {}, allowing processing: {}",
                    idempotencyKey, e.getMessage());
            return true;
        }
    }

    /**
     * Generates an idempotency key from message content when no messageId is available.
     * Uses SHA-256 hash of the content.
     *
     * @param deviceId  the device identifier
     * @param timestamp the message timestamp (epoch millis)
     * @param value     the message value/payload
     * @return a deterministic hash-based key
     */
    public String generateContentHash(String deviceId, long timestamp, String value) {
        final var content = "%s:%d:%s".formatted(deviceId, timestamp, value);

        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            final var hashBytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java, this should never happen
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

