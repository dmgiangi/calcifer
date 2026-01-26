package dev.dmgiangi.core.server.infrastructure.persistence.redis;

import dev.dmgiangi.core.server.config.RedisTestContainerConfiguration;
import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.infrastructure.configuration.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;


@DataRedisTest
@Import({
        RedisConfig.class,
        RedisDeviceStateRepositoryAdapter.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@DisplayName("RedisDeviceStateRepositoryAdapter Integration Tests")
class RedisDeviceStateRepositoryAdapterIntegrationTest implements RedisTestContainerConfiguration {


    @Autowired
    private RedisDeviceStateRepositoryAdapter repository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test
        final var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Nested
    @DisplayName("UserIntent operations")
    class UserIntentOperations {

        @Test
        @DisplayName("should save and retrieve RELAY UserIntent")
        void shouldSaveAndRetrieveRelayUserIntent() {
            final var deviceId = new DeviceId("controller-1", "relay-1");
            final var intent = UserIntent.now(deviceId, DeviceType.RELAY, new RelayValue(true));

            repository.saveUserIntent(intent);

            final var result = repository.findUserIntent(deviceId);

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo(deviceId);
            assertThat(result.get().type()).isEqualTo(DeviceType.RELAY);
            assertThat(result.get().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should save and retrieve FAN UserIntent")
        void shouldSaveAndRetrieveFanUserIntent() {
            final var deviceId = new DeviceId("controller-1", "fan-1");
            final var intent = UserIntent.now(deviceId, DeviceType.FAN, new FanValue(128));

            repository.saveUserIntent(intent);

            final var result = repository.findUserIntent(deviceId);

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo(deviceId);
            assertThat(result.get().type()).isEqualTo(DeviceType.FAN);
            assertThat(result.get().value()).isEqualTo(new FanValue(128));
        }

        @Test
        @DisplayName("should return empty when UserIntent not found")
        void shouldReturnEmptyWhenUserIntentNotFound() {
            final var deviceId = new DeviceId("non-existent", "device");

            final var result = repository.findUserIntent(deviceId);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("ReportedDeviceState operations")
    class ReportedDeviceStateOperations {

        @Test
        @DisplayName("should save and retrieve known RELAY ReportedDeviceState")
        void shouldSaveAndRetrieveKnownRelayReportedState() {
            final var deviceId = new DeviceId("controller-1", "relay-1");
            final var state = ReportedDeviceState.known(deviceId, DeviceType.RELAY, new RelayValue(false));

            repository.saveReportedState(state);

            final var result = repository.findReportedState(deviceId);

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo(deviceId);
            assertThat(result.get().type()).isEqualTo(DeviceType.RELAY);
            assertThat(result.get().value()).isEqualTo(new RelayValue(false));
            assertThat(result.get().isKnown()).isTrue();
        }

        @Test
        @DisplayName("should save and retrieve unknown ReportedDeviceState")
        void shouldSaveAndRetrieveUnknownReportedState() {
            final var deviceId = new DeviceId("controller-1", "relay-1");
            final var state = ReportedDeviceState.unknown(deviceId, DeviceType.RELAY);

            repository.saveReportedState(state);

            final var result = repository.findReportedState(deviceId);

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo(deviceId);
            assertThat(result.get().isKnown()).isFalse();
            assertThat(result.get().value()).isNull();
        }

        @Test
        @DisplayName("should return empty when ReportedDeviceState not found")
        void shouldReturnEmptyWhenReportedStateNotFound() {
            final var deviceId = new DeviceId("non-existent", "device");

            final var result = repository.findReportedState(deviceId);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("DesiredDeviceState operations")
    class DesiredDeviceStateOperations {

        @Test
        @DisplayName("should save and retrieve RELAY DesiredDeviceState")
        void shouldSaveAndRetrieveRelayDesiredState() {
            final var deviceId = new DeviceId("controller-1", "relay-1");
            final var state = new DesiredDeviceState(deviceId, DeviceType.RELAY, new RelayValue(true));

            repository.saveDesiredState(state);

            final var result = repository.findDesiredState(deviceId);

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo(deviceId);
            assertThat(result.get().type()).isEqualTo(DeviceType.RELAY);
            assertThat(result.get().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should save and retrieve FAN DesiredDeviceState")
        void shouldSaveAndRetrieveFanDesiredState() {
            final var deviceId = new DeviceId("controller-1", "fan-1");
            final var state = new DesiredDeviceState(deviceId, DeviceType.FAN, new FanValue(255));

            repository.saveDesiredState(state);

            final var result = repository.findDesiredState(deviceId);

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo(deviceId);
            assertThat(result.get().type()).isEqualTo(DeviceType.FAN);
            assertThat(result.get().value()).isEqualTo(new FanValue(255));
        }

        @Test
        @DisplayName("should add OUTPUT device to index when saving DesiredDeviceState")
        void shouldAddOutputDeviceToIndexWhenSavingDesiredState() {
            final var deviceId = new DeviceId("controller-1", "relay-1");
            final var state = new DesiredDeviceState(deviceId, DeviceType.RELAY, new RelayValue(true));

            repository.saveDesiredState(state);

            // Verify device is in the index
            final var indexMembers = redisTemplate.opsForSet().members("index:active:outputs");
            assertThat(indexMembers).contains("device:controller-1:relay-1");
        }

        @Test
        @DisplayName("should return empty when DesiredDeviceState not found")
        void shouldReturnEmptyWhenDesiredStateNotFound() {
            final var deviceId = new DeviceId("non-existent", "device");

            final var result = repository.findDesiredState(deviceId);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("TwinSnapshot operations")
    class TwinSnapshotOperations {

        @Test
        @DisplayName("should return all three states atomically")
        void shouldReturnAllThreeStatesAtomically() {
            final var deviceId = new DeviceId("controller-1", "relay-1");
            final var intent = UserIntent.now(deviceId, DeviceType.RELAY, new RelayValue(true));
            final var reported = ReportedDeviceState.known(deviceId, DeviceType.RELAY, new RelayValue(false));
            final var desired = new DesiredDeviceState(deviceId, DeviceType.RELAY, new RelayValue(true));

            repository.saveUserIntent(intent);
            repository.saveReportedState(reported);
            repository.saveDesiredState(desired);

            final var snapshot = repository.findTwinSnapshot(deviceId);

            assertThat(snapshot).isPresent();
            assertThat(snapshot.get().id()).isEqualTo(deviceId);
            assertThat(snapshot.get().getIntent()).isPresent();
            assertThat(snapshot.get().getReported()).isPresent();
            assertThat(snapshot.get().getDesired()).isPresent();
            assertThat(snapshot.get().getIntent().get().value()).isEqualTo(new RelayValue(true));
            assertThat(snapshot.get().getReported().get().value()).isEqualTo(new RelayValue(false));
            assertThat(snapshot.get().getDesired().get().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should return snapshot with partial states")
        void shouldReturnSnapshotWithPartialStates() {
            final var deviceId = new DeviceId("controller-1", "relay-1");
            final var intent = UserIntent.now(deviceId, DeviceType.RELAY, new RelayValue(true));

            repository.saveUserIntent(intent);

            final var snapshot = repository.findTwinSnapshot(deviceId);

            assertThat(snapshot).isPresent();
            assertThat(snapshot.get().getIntent()).isPresent();
            assertThat(snapshot.get().getReported()).isEmpty();
            assertThat(snapshot.get().getDesired()).isEmpty();
        }

        @Test
        @DisplayName("should return empty when no states exist")
        void shouldReturnEmptyWhenNoStatesExist() {
            final var deviceId = new DeviceId("non-existent", "device");

            final var snapshot = repository.findTwinSnapshot(deviceId);

            assertThat(snapshot).isEmpty();
        }
    }

    @Nested
    @DisplayName("findAllActiveOutputDevices operations")
    class ActiveOutputDevicesOperations {

        @Test
        @DisplayName("should return all OUTPUT devices from index")
        void shouldReturnAllOutputDevicesFromIndex() {
            final var deviceId1 = new DeviceId("controller-1", "relay-1");
            final var deviceId2 = new DeviceId("controller-1", "relay-2");
            final var state1 = new DesiredDeviceState(deviceId1, DeviceType.RELAY, new RelayValue(true));
            final var state2 = new DesiredDeviceState(deviceId2, DeviceType.RELAY, new RelayValue(false));

            repository.saveDesiredState(state1);
            repository.saveDesiredState(state2);

            final var result = repository.findAllActiveOutputDevices();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(DesiredDeviceState::id)
                    .containsExactlyInAnyOrder(deviceId1, deviceId2);
        }

        @Test
        @DisplayName("should return empty list when no OUTPUT devices exist")
        void shouldReturnEmptyListWhenNoOutputDevicesExist() {
            final var result = repository.findAllActiveOutputDevices();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return multiple device types from index")
        void shouldReturnMultipleDeviceTypesFromIndex() {
            final var relayId = new DeviceId("controller-1", "relay-1");
            final var fanId = new DeviceId("controller-1", "fan-1");
            final var relayState = new DesiredDeviceState(relayId, DeviceType.RELAY, new RelayValue(true));
            final var fanState = new DesiredDeviceState(fanId, DeviceType.FAN, new FanValue(192));

            repository.saveDesiredState(relayState);
            repository.saveDesiredState(fanState);

            final var result = repository.findAllActiveOutputDevices();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(DesiredDeviceState::type)
                    .containsExactlyInAnyOrder(DeviceType.RELAY, DeviceType.FAN);
        }
    }

    @Nested
    @DisplayName("Optimistic Locking Operations")
    class OptimisticLockingOperations {

        @Test
        @DisplayName("Should handle concurrent intent updates with optimistic locking")
        void shouldHandleConcurrentIntentUpdates() throws InterruptedException {
            final var deviceId = new DeviceId("concurrent-ctrl", "relay-1");
            final var threadCount = 5;
            final var latch = new CountDownLatch(1);
            final var completionLatch = new CountDownLatch(threadCount);
            final var successCount = new AtomicInteger(0);
            final var errors = new ArrayList<Throwable>();

            try (final var executor = Executors.newFixedThreadPool(threadCount)) {
                for (int i = 0; i < threadCount; i++) {
                    final var value = i % 2 == 0; // Alternate between true and false
                    executor.submit(() -> {
                        try {
                            latch.await(); // Wait for all threads to be ready
                            final var intent = UserIntent.now(deviceId, DeviceType.RELAY, new RelayValue(value));
                            repository.saveUserIntent(intent);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            synchronized (errors) {
                                errors.add(e);
                            }
                        } finally {
                            completionLatch.countDown();
                        }
                    });
                }

                latch.countDown(); // Release all threads simultaneously
                final var completed = completionLatch.await(10, TimeUnit.SECONDS);

                assertThat(completed).isTrue();
                assertThat(successCount.get()).isEqualTo(threadCount);
                assertThat(errors).isEmpty();
            }

            // Verify final state exists
            final var finalIntent = repository.findUserIntent(deviceId);
            assertThat(finalIntent).isPresent();
        }

        @Test
        @DisplayName("Should handle concurrent desired state updates with optimistic locking")
        void shouldHandleConcurrentDesiredStateUpdates() throws InterruptedException {
            final var deviceId = new DeviceId("concurrent-ctrl", "fan-1");
            final var threadCount = 5;
            final var latch = new CountDownLatch(1);
            final var completionLatch = new CountDownLatch(threadCount);
            final var successCount = new AtomicInteger(0);
            final var errors = new ArrayList<Throwable>();

            try (final var executor = Executors.newFixedThreadPool(threadCount)) {
                for (int i = 0; i < threadCount; i++) {
                    final var fanSpeed = (i + 1) * 50; // Different speeds: 50, 100, 150, 200, 250
                    executor.submit(() -> {
                        try {
                            latch.await();
                            final var desired = new DesiredDeviceState(deviceId, DeviceType.FAN, new FanValue(fanSpeed));
                            repository.saveDesiredState(desired);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            synchronized (errors) {
                                errors.add(e);
                            }
                        } finally {
                            completionLatch.countDown();
                        }
                    });
                }

                latch.countDown();
                final var completed = completionLatch.await(10, TimeUnit.SECONDS);

                assertThat(completed).isTrue();
                assertThat(successCount.get()).isEqualTo(threadCount);
                assertThat(errors).isEmpty();
            }

            // Verify final state exists and is valid
            final var finalDesired = repository.findDesiredState(deviceId);
            assertThat(finalDesired).isPresent();
            assertThat(finalDesired.get().type()).isEqualTo(DeviceType.FAN);
        }

        @Test
        @DisplayName("Should handle concurrent mixed operations on same device")
        void shouldHandleConcurrentMixedOperations() throws InterruptedException {
            final var deviceId = new DeviceId("mixed-ctrl", "relay-1");
            final var threadCount = 6;
            final var latch = new CountDownLatch(1);
            final var completionLatch = new CountDownLatch(threadCount);
            final var successCount = new AtomicInteger(0);
            final var errors = new ArrayList<Throwable>();

            try (final var executor = Executors.newFixedThreadPool(threadCount)) {
                // 2 threads for intent, 2 for desired, 2 for reported
                for (int i = 0; i < threadCount; i++) {
                    final var operation = i % 3;
                    final var value = i % 2 == 0;
                    executor.submit(() -> {
                        try {
                            latch.await();
                            switch (operation) {
                                case 0 -> repository.saveUserIntent(
                                        UserIntent.now(deviceId, DeviceType.RELAY, new RelayValue(value)));
                                case 1 -> repository.saveDesiredState(
                                        new DesiredDeviceState(deviceId, DeviceType.RELAY, new RelayValue(value)));
                                case 2 -> repository.saveReportedState(
                                        ReportedDeviceState.known(deviceId, DeviceType.RELAY, new RelayValue(value)));
                            }
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            synchronized (errors) {
                                errors.add(e);
                            }
                        } finally {
                            completionLatch.countDown();
                        }
                    });
                }

                latch.countDown();
                final var completed = completionLatch.await(10, TimeUnit.SECONDS);

                assertThat(completed).isTrue();
                assertThat(successCount.get()).isEqualTo(threadCount);
                assertThat(errors).isEmpty();
            }

            // Verify device twin has data
            final var snapshot = repository.findTwinSnapshot(deviceId);
            assertThat(snapshot).isPresent();
        }
    }
}

