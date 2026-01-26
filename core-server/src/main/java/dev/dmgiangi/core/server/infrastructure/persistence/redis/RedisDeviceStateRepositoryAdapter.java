package dev.dmgiangi.core.server.infrastructure.persistence.redis;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceCapability;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.ReportedDeviceState;
import dev.dmgiangi.core.server.domain.model.UserIntent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;


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

    // ========== Desired State Methods ==========

    @Override
    public void saveDesiredState(DesiredDeviceState state) {
        final var key = generateKey(state.id());

        // 1. Save state to Hash field
        redisTemplate.opsForHash().put(key, HASH_FIELD_DESIRED, state);

        // 2. Manage index for Reconciler
        if (state.type().capability == DeviceCapability.OUTPUT) {
            redisTemplate.opsForSet().add(INDEX_OUTPUTS, key);
        } else {
            redisTemplate.opsForSet().remove(INDEX_OUTPUTS, key);
        }

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
        redisTemplate.opsForHash().put(key, HASH_FIELD_INTENT, intent);
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
        redisTemplate.opsForHash().put(key, HASH_FIELD_REPORTED, state);
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
        if (value != null && type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }

}