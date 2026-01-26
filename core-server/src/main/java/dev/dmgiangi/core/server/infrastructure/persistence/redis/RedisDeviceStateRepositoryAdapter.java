package dev.dmgiangi.core.server.infrastructure.persistence.redis;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;


@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisDeviceStateRepositoryAdapter implements DeviceStateRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    // Key prefix for device state storage
    private static final String KEY_PREFIX = "device:";
    private static final String INDEX_OUTPUTS = "index:active:outputs";

    // Hash field names for the three-state digital twin model
    private static final String HASH_FIELD_INTENT = "intent";
    private static final String HASH_FIELD_REPORTED = "reported";
    private static final String HASH_FIELD_DESIRED = "desired";

    // Optimistic locking retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 10;

    // ========== Desired State Methods ==========

    @Override
    public void saveDesiredState(DesiredDeviceState state) {
        final var key = generateKey(state.id());

        executeWithOptimisticLock(key, operations -> {
            // 1. Save state to Hash field
            operations.opsForHash().put(key, HASH_FIELD_DESIRED, state);

            // 2. Manage index for Reconciler
            if (state.type().capability == DeviceCapability.OUTPUT) {
                operations.opsForSet().add(INDEX_OUTPUTS, key);
            } else {
                operations.opsForSet().remove(INDEX_OUTPUTS, key);
            }
        });

        log.debug("Saved desired state for device {} to Redis Hash", key);
    }

    @Override
    public Optional<DesiredDeviceState> findDesiredState(DeviceId id) {
        final var key = generateKey(id);
        final var value = redisTemplate.opsForHash().get(key, HASH_FIELD_DESIRED);
        return Optional.ofNullable(value)
                .filter(DesiredDeviceState.class::isInstance)
                .map(DesiredDeviceState.class::cast);
    }

    @Override
    public List<DesiredDeviceState> findAllActiveOutputDevices() {
        final var keys = redisTemplate.opsForSet().members(INDEX_OUTPUTS);

        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        return keys.stream()
                .map(Object::toString)
                .map(key -> redisTemplate.opsForHash().get(key, HASH_FIELD_DESIRED))
                .filter(DesiredDeviceState.class::isInstance)
                .map(DesiredDeviceState.class::cast)
                .toList();
    }

    // ========== User Intent Methods ==========

    @Override
    public void saveUserIntent(UserIntent intent) {
        final var key = generateKey(intent.id());

        executeWithOptimisticLock(key, operations -> {
            operations.opsForHash().put(key, HASH_FIELD_INTENT, intent);
        });

        log.debug("Saved user intent for device {} to Redis Hash", key);
    }

    @Override
    public Optional<UserIntent> findUserIntent(DeviceId id) {
        final var key = generateKey(id);
        final var result = redisTemplate.opsForHash().get(key, HASH_FIELD_INTENT);
        return Optional.ofNullable(result)
                .filter(UserIntent.class::isInstance)
                .map(UserIntent.class::cast);
    }

    // ========== Reported State Methods ==========

    @Override
    public void saveReportedState(ReportedDeviceState state) {
        final var key = generateKey(state.id());

        executeWithOptimisticLock(key, operations -> {
            operations.opsForHash().put(key, HASH_FIELD_REPORTED, state);
        });

        log.debug("Saved reported state for device {} to Redis Hash", key);
    }

    @Override
    public Optional<ReportedDeviceState> findReportedState(DeviceId id) {
        final var key = generateKey(id);
        final var result = redisTemplate.opsForHash().get(key, HASH_FIELD_REPORTED);
        return Optional.ofNullable(result)
                .filter(ReportedDeviceState.class::isInstance)
                .map(ReportedDeviceState.class::cast);
    }

    // ========== Digital Twin Snapshot ==========

    @Override
    public Optional<DeviceTwinSnapshot> findTwinSnapshot(DeviceId id) {
        final var key = generateKey(id);
        final var hashKeys = List.<Object>of(HASH_FIELD_INTENT, HASH_FIELD_REPORTED, HASH_FIELD_DESIRED);
        final var values = redisTemplate.opsForHash().multiGet(key, hashKeys);

        // values list order matches hashKeys order: [intent, reported, desired]
        final var intent = extractValue(values.get(0), UserIntent.class);
        final var reported = extractValue(values.get(1), ReportedDeviceState.class);
        final var desired = extractValue(values.get(2), DesiredDeviceState.class);

        // Return empty if no state exists for this device
        if (intent == null && reported == null && desired == null) {
            return Optional.empty();
        }

        // Derive DeviceType from the first non-null state
        final var type = intent != null ? intent.type()
                       : reported != null ? reported.type()
                       : desired.type();

        return Optional.of(new DeviceTwinSnapshot(id, type, intent, reported, desired));
    }

    // ========== Helper Methods ==========

    private String generateKey(DeviceId id) {
        return KEY_PREFIX + id.toString();
    }

    private <T> T extractValue(Object value, Class<T> type) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }

    // ========== Optimistic Locking Helper ==========

    /**
     * Executes Redis operations with optimistic locking using WATCH/MULTI/EXEC.
     * <p>
     * If the watched key is modified by another client during the transaction,
     * the operation is retried up to {@link #MAX_RETRIES} times with exponential backoff.
     *
     * @param key      the Redis key to watch for concurrent modifications
     * @param commands the Redis commands to execute within the transaction
     * @throws OptimisticLockingFailureException if all retries are exhausted
     */
    @SuppressWarnings("unchecked")
    private void executeWithOptimisticLock(String key, Consumer<RedisOperations<String, Object>> commands) {
        int attempts = 0; // mutable - tracks retry attempts for optimistic locking

        while (attempts < MAX_RETRIES) {
            final var result = redisTemplate.execute(new SessionCallback<List<Object>>() {
                @Override
                public List<Object> execute(@Nonnull RedisOperations redisOps) throws DataAccessException {
                    redisOps.watch(key);
                    redisOps.multi();
                    commands.accept(redisOps);
                    return redisOps.exec();
                }
            });

            // EXEC returns non-null list if transaction succeeded
            if (result != null) {
                if (attempts > 0) {
                    log.debug("Optimistic lock succeeded after {} retries for key {}", attempts, key);
                }
                return;
            }

            // Transaction aborted due to concurrent modification
            attempts++;
            log.warn("Optimistic lock conflict detected for key {}, attempt {}/{}", key, attempts, MAX_RETRIES);

            if (attempts < MAX_RETRIES) {
                try {
                    // Exponential backoff: 10ms, 20ms, 40ms...
                    Thread.sleep(RETRY_BASE_DELAY_MS * (1L << (attempts - 1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OptimisticLockingFailureException(
                            "Interrupted while retrying optimistic lock for key: " + key, e);
                }
            }
        }

        throw new OptimisticLockingFailureException(
                "Failed to acquire optimistic lock for key " + key + " after " + MAX_RETRIES + " attempts");
    }

}