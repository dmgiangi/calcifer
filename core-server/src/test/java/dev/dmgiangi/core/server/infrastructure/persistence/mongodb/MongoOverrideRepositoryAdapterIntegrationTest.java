package dev.dmgiangi.core.server.infrastructure.persistence.mongodb;

import dev.dmgiangi.core.server.config.MongoTestContainerConfiguration;
import dev.dmgiangi.core.server.config.RedisTestContainerConfiguration;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.model.FanValue;
import dev.dmgiangi.core.server.domain.model.RelayValue;
import dev.dmgiangi.core.server.domain.port.OverrideRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.infrastructure.configuration.RedisConfig;
import dev.dmgiangi.core.server.infrastructure.persistence.redis.RedisOverrideCacheAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.data.redis.test.autoconfigure.AutoConfigureDataRedis;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MongoOverrideRepositoryAdapter.
 * Tests MongoDB persistence with Redis write-through caching.
 * Uses slice test approach to avoid loading full application context.
 */
@DataMongoTest
@AutoConfigureDataRedis
@Import({
        RedisConfig.class,
        RedisOverrideCacheAdapter.class,
        MongoOverrideRepositoryAdapter.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@DisplayName("MongoOverrideRepositoryAdapter Integration Tests")
class MongoOverrideRepositoryAdapterIntegrationTest
        implements MongoTestContainerConfiguration, RedisTestContainerConfiguration {

    @Autowired
    private OverrideRepository overrideRepository;

    @Autowired
    private SpringDataOverrideRepository springDataRepository;

    @Autowired
    private RedisOverrideCacheAdapter redisCache;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DEVICE_ID = "controller1:relay1";
    private static final String SYSTEM_ID = "termocamino-system";

    // Use DeviceValue types instead of primitives for proper Jackson serialization
    private static final RelayValue RELAY_ON = new RelayValue(true);
    private static final RelayValue RELAY_OFF = new RelayValue(false);
    private static final FanValue FAN_SPEED_2 = new FanValue(2);

    @BeforeEach
    void setUp() {
        // Clean up MongoDB
        springDataRepository.deleteAll();
        // Clean up Redis completely (flush all keys)
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Nested
    @DisplayName("CRUD Operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve override by target and category")
        void shouldSaveAndRetrieveOverride() {
            final var override = createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_ON);

            final var saved = overrideRepository.save(override);

            assertThat(saved.id()).isNotNull();
            assertThat(saved.version()).isNotNull();

            final var found = overrideRepository.findByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);
            assertThat(found).isPresent();
            assertThat(found.get().targetId()).isEqualTo(DEVICE_ID);
            assertThat(found.get().value()).isEqualTo(RELAY_ON);
        }

        @Test
        @DisplayName("should find all active overrides for target")
        void shouldFindAllActiveOverrides() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_OFF));
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MAINTENANCE, RELAY_ON));

            final var active = overrideRepository.findActiveByTarget(DEVICE_ID);

            assertThat(active).hasSize(2);
            // Should be ordered by priority (highest first)
            assertThat(active.get(0).category()).isEqualTo(OverrideCategory.MAINTENANCE);
            assertThat(active.get(1).category()).isEqualTo(OverrideCategory.MANUAL);
        }

        @Test
        @DisplayName("should find effective override (highest priority)")
        void shouldFindEffectiveOverride() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_OFF));
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.EMERGENCY, RELAY_ON));

            final var effective = overrideRepository.findEffectiveByTarget(DEVICE_ID);

            assertThat(effective).isPresent();
            assertThat(effective.get().category()).isEqualTo(OverrideCategory.EMERGENCY);
            assertThat(effective.get().value()).isEqualTo(RELAY_ON);
        }

        @Test
        @DisplayName("should delete override by target and category")
        void shouldDeleteOverride() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_ON));

            overrideRepository.deleteByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);

            final var found = overrideRepository.findByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should delete all overrides for target")
        void shouldDeleteAllOverrides() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_OFF));
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MAINTENANCE, RELAY_ON));

            overrideRepository.deleteAllByTarget(DEVICE_ID);

            final var active = overrideRepository.findActiveByTarget(DEVICE_ID);
            assertThat(active).isEmpty();
        }
    }

    @Nested
    @DisplayName("Redis Cache Sync")
    class RedisCacheSync {

        @Test
        @DisplayName("should cache override in Redis after save")
        void shouldCacheOverrideAfterSave() {
            final var override = createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_ON);

            overrideRepository.save(override);

            // Verify Redis cache has the override
            final var cached = redisCache.findByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);
            assertThat(cached).isPresent();
            assertThat(cached.get().value()).isEqualTo(RELAY_ON);
        }

        @Test
        @DisplayName("should evict from Redis cache after delete")
        void shouldEvictFromCacheAfterDelete() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_ON));

            overrideRepository.deleteByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);

            // Verify Redis cache is empty
            final var cached = redisCache.findByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);
            assertThat(cached).isEmpty();
        }

        @Test
        @DisplayName("should read from cache when available")
        void shouldReadFromCacheWhenAvailable() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_ON));

            // First read populates cache, second read should use cache
            final var first = overrideRepository.findByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);
            final var second = overrideRepository.findByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);

            assertThat(first).isPresent();
            assertThat(second).isPresent();
            assertThat(first.get().value()).isEqualTo(second.get().value());
        }
    }

    @Nested
    @DisplayName("Expiration Handling")
    class ExpirationHandling {

        @Test
        @DisplayName("should find expired overrides")
        void shouldFindExpiredOverrides() {
            // Create an already expired override
            final var expiredAt = Instant.now().minusSeconds(60);
            final var expired = new OverrideData(
                    DEVICE_ID + ":MANUAL", DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL,
                    RELAY_ON, "Test", expiredAt, Instant.now().minusSeconds(120), "test-user", null
            );
            overrideRepository.save(expired);

            final var expiredList = overrideRepository.findExpired();

            assertThat(expiredList).hasSize(1);
            assertThat(expiredList.get(0).targetId()).isEqualTo(DEVICE_ID);
        }

        @Test
        @DisplayName("should not include expired in active overrides")
        void shouldNotIncludeExpiredInActive() {
            // Create an already expired override
            final var expiredAt = Instant.now().minusSeconds(60);
            final var expired = new OverrideData(
                    DEVICE_ID + ":MANUAL", DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL,
                    RELAY_ON, "Test", expiredAt, Instant.now().minusSeconds(120), "test-user", null
            );
            overrideRepository.save(expired);

            // Create a valid override
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MAINTENANCE, RELAY_OFF));

            final var active = overrideRepository.findActiveByTarget(DEVICE_ID);

            assertThat(active).hasSize(1);
            assertThat(active.get(0).category()).isEqualTo(OverrideCategory.MAINTENANCE);
        }
    }

    @Nested
    @DisplayName("Category Precedence")
    class CategoryPrecedence {

        @Test
        @DisplayName("should replace override at same target and category")
        void shouldReplaceAtSameTargetAndCategory() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_OFF));
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_ON));

            final var active = overrideRepository.findActiveByTarget(DEVICE_ID);

            assertThat(active).hasSize(1);
            assertThat(active.get(0).value()).isEqualTo(RELAY_ON);
        }

        @Test
        @DisplayName("should maintain multiple categories for same target")
        void shouldMaintainMultipleCategories() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_OFF));
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.SCHEDULED, FAN_SPEED_2));
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MAINTENANCE, RELAY_ON));
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.EMERGENCY, RELAY_OFF));

            final var active = overrideRepository.findActiveByTarget(DEVICE_ID);

            assertThat(active).hasSize(4);
            // Verify order: EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL
            assertThat(active.get(0).category()).isEqualTo(OverrideCategory.EMERGENCY);
            assertThat(active.get(1).category()).isEqualTo(OverrideCategory.MAINTENANCE);
            assertThat(active.get(2).category()).isEqualTo(OverrideCategory.SCHEDULED);
            assertThat(active.get(3).category()).isEqualTo(OverrideCategory.MANUAL);
        }

        @Test
        @DisplayName("should support both device and system overrides")
        void shouldSupportDeviceAndSystemOverrides() {
            overrideRepository.save(createOverride(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, RELAY_ON));
            overrideRepository.save(createOverride(SYSTEM_ID, OverrideScope.SYSTEM, OverrideCategory.MAINTENANCE, RELAY_OFF));

            final var deviceOverrides = overrideRepository.findActiveByTarget(DEVICE_ID);
            final var systemOverrides = overrideRepository.findActiveByTarget(SYSTEM_ID);

            assertThat(deviceOverrides).hasSize(1);
            assertThat(deviceOverrides.get(0).scope()).isEqualTo(OverrideScope.DEVICE);

            assertThat(systemOverrides).hasSize(1);
            assertThat(systemOverrides.get(0).scope()).isEqualTo(OverrideScope.SYSTEM);
        }
    }

    private OverrideData createOverride(String targetId, OverrideScope scope, OverrideCategory category, DeviceValue value) {
        return OverrideData.create(targetId, scope, category, value, "Test reason", null, "test-user");
    }

    private OverrideData createOverrideWithTtl(String targetId, OverrideScope scope, OverrideCategory category,
                                               DeviceValue value, Duration ttl) {
        final var expiresAt = Instant.now().plus(ttl);
        return OverrideData.create(targetId, scope, category, value, "Test reason", expiresAt, "test-user");
    }
}

