package dev.dmgiangi.core.server.infrastructure.persistence.mongodb;

import dev.dmgiangi.core.server.config.MongoTestContainerConfiguration;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataMongoTest
@Import(MongoFunctionalSystemRepositoryAdapter.class)
@DisplayName("MongoFunctionalSystemRepositoryAdapter Integration Tests")
class MongoFunctionalSystemRepositoryAdapterIntegrationTest implements MongoTestContainerConfiguration {

    @Autowired
    private MongoFunctionalSystemRepositoryAdapter repository;

    @Autowired
    private SpringDataFunctionalSystemRepository springDataRepository;

    @BeforeEach
    void setUp() {
        springDataRepository.deleteAll();
    }

    private FunctionalSystemData createTestSystem(String id) {
        return new FunctionalSystemData(
                id,
                "TERMOCAMINO",
                "Test System " + id,
                Map.of("mode", "AUTO", "targetTemp", 22.0),
                Set.of("controller1:relay1", "controller1:fan1"),
                Map.of("relay1", false, "fan1", 0),
                Instant.now(),
                Instant.now(),
                "test-user",
                null
        );
    }

    @Nested
    @DisplayName("CRUD Operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve system by ID")
        void shouldSaveAndRetrieveSystemById() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);

            final var saved = repository.save(system);

            assertThat(saved.id()).isEqualTo(systemId);
            assertThat(saved.version()).isNotNull();

            final var found = repository.findById(systemId);

            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(systemId);
            assertThat(found.get().type()).isEqualTo("TERMOCAMINO");
            assertThat(found.get().name()).isEqualTo("Test System " + systemId);
        }

        @Test
        @DisplayName("should return empty when system not found")
        void shouldReturnEmptyWhenSystemNotFound() {
            final var result = repository.findById("non-existent-id");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should find all systems")
        void shouldFindAllSystems() {
            final var system1 = createTestSystem(UUID.randomUUID().toString());
            final var system2 = createTestSystem(UUID.randomUUID().toString());

            repository.save(system1);
            repository.save(system2);

            final var all = repository.findAll();

            assertThat(all).hasSize(2);
        }

        @Test
        @DisplayName("should delete system by ID")
        void shouldDeleteSystemById() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);
            repository.save(system);

            assertThat(repository.existsById(systemId)).isTrue();

            repository.deleteById(systemId);

            assertThat(repository.existsById(systemId)).isFalse();
            assertThat(repository.findById(systemId)).isEmpty();
        }

        @Test
        @DisplayName("should check existence by ID")
        void shouldCheckExistenceById() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);

            assertThat(repository.existsById(systemId)).isFalse();

            repository.save(system);

            assertThat(repository.existsById(systemId)).isTrue();
        }
    }

    @Nested
    @DisplayName("Device Membership")
    class DeviceMembership {

        @Test
        @DisplayName("should find system by device ID")
        void shouldFindSystemByDeviceId() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);
            repository.save(system);

            final var found = repository.findByDeviceId("controller1:relay1");

            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(systemId);
        }

        @Test
        @DisplayName("should return empty when device not in any system")
        void shouldReturnEmptyWhenDeviceNotInAnySystem() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);
            repository.save(system);

            final var found = repository.findByDeviceId("controller2:unknown");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should update device membership")
        void shouldUpdateDeviceMembership() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);
            final var saved = repository.save(system);

            // Add a new device
            final var newDeviceIds = new java.util.HashSet<>(saved.deviceIds());
            newDeviceIds.add("controller2:relay2");
            final var updated = new FunctionalSystemData(
                    saved.id(), saved.type(), saved.name(), saved.configuration(),
                    Set.copyOf(newDeviceIds), saved.failSafeDefaults(),
                    saved.createdAt(), Instant.now(), saved.createdBy(), saved.version()
            );

            final var result = repository.save(updated);

            assertThat(result.deviceIds()).contains("controller2:relay2");
            assertThat(repository.findByDeviceId("controller2:relay2")).isPresent();
        }
    }

    @Nested
    @DisplayName("Optimistic Locking")
    class OptimisticLocking {

        @Test
        @DisplayName("should increment version on save")
        void shouldIncrementVersionOnSave() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);

            final var saved1 = repository.save(system);
            assertThat(saved1.version()).isEqualTo(0L);

            final var updated = new FunctionalSystemData(
                    saved1.id(), saved1.type(), "Updated Name", saved1.configuration(),
                    saved1.deviceIds(), saved1.failSafeDefaults(),
                    saved1.createdAt(), Instant.now(), saved1.createdBy(), saved1.version()
            );

            final var saved2 = repository.save(updated);
            assertThat(saved2.version()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should throw OptimisticLockingFailureException on stale version")
        void shouldThrowOnStaleVersion() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);

            final var saved = repository.save(system);

            // Simulate concurrent modification by saving with same version
            final var update1 = new FunctionalSystemData(
                    saved.id(), saved.type(), "Update 1", saved.configuration(),
                    saved.deviceIds(), saved.failSafeDefaults(),
                    saved.createdAt(), Instant.now(), saved.createdBy(), saved.version()
            );
            repository.save(update1);

            // Try to save with stale version (version 0 instead of 1)
            final var staleUpdate = new FunctionalSystemData(
                    saved.id(), saved.type(), "Stale Update", saved.configuration(),
                    saved.deviceIds(), saved.failSafeDefaults(),
                    saved.createdAt(), Instant.now(), saved.createdBy(), saved.version()
            );

            assertThatThrownBy(() -> repository.save(staleUpdate))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }
    }

    @Nested
    @DisplayName("Configuration Updates")
    class ConfigurationUpdates {

        @Test
        @DisplayName("should update system configuration")
        void shouldUpdateSystemConfiguration() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);
            final var saved = repository.save(system);

            final var newConfig = Map.<String, Object>of(
                    "mode", "MANUAL",
                    "targetTemp", 25.0,
                    "schedule", "08:00-22:00"
            );

            final var updated = new FunctionalSystemData(
                    saved.id(), saved.type(), saved.name(), newConfig,
                    saved.deviceIds(), saved.failSafeDefaults(),
                    saved.createdAt(), Instant.now(), saved.createdBy(), saved.version()
            );

            final var result = repository.save(updated);

            assertThat(result.configuration()).containsEntry("mode", "MANUAL");
            assertThat(result.configuration()).containsEntry("targetTemp", 25.0);
            assertThat(result.configuration()).containsEntry("schedule", "08:00-22:00");
        }

        @Test
        @DisplayName("should update fail-safe defaults")
        void shouldUpdateFailSafeDefaults() {
            final var systemId = UUID.randomUUID().toString();
            final var system = createTestSystem(systemId);
            final var saved = repository.save(system);

            final var newFailSafe = Map.<String, Object>of(
                    "relay1", true,
                    "fan1", 2
            );

            final var updated = new FunctionalSystemData(
                    saved.id(), saved.type(), saved.name(), saved.configuration(),
                    saved.deviceIds(), newFailSafe,
                    saved.createdAt(), Instant.now(), saved.createdBy(), saved.version()
            );

            final var result = repository.save(updated);

            assertThat(result.failSafeDefaults()).containsEntry("relay1", true);
            assertThat(result.failSafeDefaults()).containsEntry("fan1", 2);
        }
    }
}

