package dev.dmgiangi.core.server.infrastructure.scheduling;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.event.DesiredStateCalculatedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.infrastructure.health.InfrastructureHealthGate;
import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Integration tests for ImmediateReconciler.
 * Tests event flow, debounce mechanism, and at-least-once delivery semantics.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImmediateReconciler Integration Tests")
class ImmediateReconcilerIntegrationTest {

    private static final DeviceId RELAY_DEVICE_ID = new DeviceId("controller1", "relay1");
    private static final DeviceId FAN_DEVICE_ID = new DeviceId("controller1", "fan1");
    private static final long DEBOUNCE_MS = 50;

    @Mock
    private DeviceStateRepository deviceStateRepository;

    @Mock
    private InfrastructureHealthGate infrastructureHealthGate;

    private MeterRegistry meterRegistry;
    private List<DeviceCommandEvent> capturedCommands;
    private ApplicationEventPublisher testEventPublisher;
    private ImmediateReconciler reconciler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        capturedCommands = Collections.synchronizedList(new ArrayList<>());

        // Create a test event publisher that captures DeviceCommandEvent
        testEventPublisher = event -> {
            if (event instanceof DeviceCommandEvent cmd) {
                capturedCommands.add(cmd);
            }
        };

        reconciler = new ImmediateReconciler(
                deviceStateRepository,
                testEventPublisher,
                infrastructureHealthGate,
                meterRegistry,
                DEBOUNCE_MS
        );
    }

    @Nested
    @DisplayName("Event Flow Tests")
    class EventFlowTests {

        @Test
        @DisplayName("should send DeviceCommandEvent after receiving DesiredStateCalculatedEvent")
        void shouldSendCommandAfterDesiredStateCalculated() throws InterruptedException {
            // Given: Infrastructure is healthy and device is not converged
            when(infrastructureHealthGate.isHealthy()).thenReturn(true);
            final var snapshot = createNonConvergedSnapshot(RELAY_DEVICE_ID, DeviceType.RELAY,
                    new RelayValue(false), new RelayValue(true));
            when(deviceStateRepository.findTwinSnapshot(RELAY_DEVICE_ID)).thenReturn(Optional.of(snapshot));

            final var desiredState = new DesiredDeviceState(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var event = new DesiredStateCalculatedEvent(this, desiredState);

            // When: Event is received
            reconciler.onDesiredStateCalculated(event);

            // Then: Wait for debounce and verify command was sent
            Thread.sleep(DEBOUNCE_MS + 50);
            assertThat(capturedCommands).hasSize(1);
            assertThat(capturedCommands.get(0).deviceId()).isEqualTo(RELAY_DEVICE_ID);
            assertThat(capturedCommands.get(0).value()).isEqualTo(true);
        }

        @Test
        @DisplayName("should send FAN command with correct speed value")
        void shouldSendFanCommandWithCorrectSpeed() throws InterruptedException {
            // Given
            when(infrastructureHealthGate.isHealthy()).thenReturn(true);
            final var snapshot = createNonConvergedSnapshot(FAN_DEVICE_ID, DeviceType.FAN,
                    new FanValue(0), new FanValue(3));
            when(deviceStateRepository.findTwinSnapshot(FAN_DEVICE_ID)).thenReturn(Optional.of(snapshot));

            final var desiredState = new DesiredDeviceState(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(3));
            final var event = new DesiredStateCalculatedEvent(this, desiredState);

            // When
            reconciler.onDesiredStateCalculated(event);

            // Then
            Thread.sleep(DEBOUNCE_MS + 50);
            assertThat(capturedCommands).hasSize(1);
            assertThat(capturedCommands.get(0).deviceId()).isEqualTo(FAN_DEVICE_ID);
            assertThat(capturedCommands.get(0).type()).isEqualTo(DeviceType.FAN);
            assertThat(capturedCommands.get(0).value()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Debounce Tests")
    class DebounceTests {

        @Test
        @DisplayName("should debounce rapid events for same device - only last value sent")
        void shouldDebounceRapidEventsForSameDevice() throws InterruptedException {
            // Given: Infrastructure is healthy
            when(infrastructureHealthGate.isHealthy()).thenReturn(true);

            // Setup snapshot to return non-converged state for final value
            final var finalSnapshot = createNonConvergedSnapshot(RELAY_DEVICE_ID, DeviceType.RELAY,
                    new RelayValue(false), new RelayValue(true));
            when(deviceStateRepository.findTwinSnapshot(RELAY_DEVICE_ID)).thenReturn(Optional.of(finalSnapshot));

            // When: Send 5 rapid events within debounce window
            for (int i = 0; i < 5; i++) {
                final var value = new RelayValue(i % 2 == 0); // alternating true/false
                final var desiredState = new DesiredDeviceState(RELAY_DEVICE_ID, DeviceType.RELAY, value);
                final var event = new DesiredStateCalculatedEvent(this, desiredState);
                reconciler.onDesiredStateCalculated(event);
                Thread.sleep(10); // Less than debounce window
            }

            // Then: Wait for debounce and verify only ONE command was sent (the last one)
            Thread.sleep(DEBOUNCE_MS + 100);
            assertThat(capturedCommands).hasSize(1);

            // Verify debounce metric was incremented
            final var debouncedCount = meterRegistry.counter("calcifer.immediate_reconciler.commands.debounced").count();
            assertThat(debouncedCount).isEqualTo(4.0); // 4 commands were debounced
        }

        @Test
        @DisplayName("should NOT debounce events for different devices")
        void shouldNotDebounceEventsForDifferentDevices() throws InterruptedException {
            // Given: Infrastructure is healthy
            when(infrastructureHealthGate.isHealthy()).thenReturn(true);

            final var relaySnapshot = createNonConvergedSnapshot(RELAY_DEVICE_ID, DeviceType.RELAY,
                    new RelayValue(false), new RelayValue(true));
            final var fanSnapshot = createNonConvergedSnapshot(FAN_DEVICE_ID, DeviceType.FAN,
                    new FanValue(0), new FanValue(2));
            when(deviceStateRepository.findTwinSnapshot(RELAY_DEVICE_ID)).thenReturn(Optional.of(relaySnapshot));
            when(deviceStateRepository.findTwinSnapshot(FAN_DEVICE_ID)).thenReturn(Optional.of(fanSnapshot));

            // When: Send events for two different devices
            final var relayDesired = new DesiredDeviceState(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var fanDesired = new DesiredDeviceState(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(2));

            reconciler.onDesiredStateCalculated(new DesiredStateCalculatedEvent(this, relayDesired));
            reconciler.onDesiredStateCalculated(new DesiredStateCalculatedEvent(this, fanDesired));

            // Then: Both commands should be sent (no debounce between different devices)
            Thread.sleep(DEBOUNCE_MS + 100);
            assertThat(capturedCommands).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Health Gate Tests")
    class HealthGateTests {

        @Test
        @DisplayName("should skip command when infrastructure is unhealthy")
        void shouldSkipCommandWhenInfrastructureUnhealthy() throws InterruptedException {
            // Given: Infrastructure is unhealthy
            when(infrastructureHealthGate.isHealthy()).thenReturn(false);

            final var desiredState = new DesiredDeviceState(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var event = new DesiredStateCalculatedEvent(this, desiredState);

            // When
            reconciler.onDesiredStateCalculated(event);

            // Then: No command should be sent
            Thread.sleep(DEBOUNCE_MS + 50);
            assertThat(capturedCommands).isEmpty();

            // Verify skip metric was incremented
            final var skippedCount = meterRegistry.counter("calcifer.immediate_reconciler.commands.skipped.unhealthy").count();
            assertThat(skippedCount).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Convergence Tests")
    class ConvergenceTests {

        @Test
        @DisplayName("should skip command when device is already converged")
        void shouldSkipCommandWhenDeviceConverged() throws InterruptedException {
            // Given: Infrastructure is healthy but device is converged
            when(infrastructureHealthGate.isHealthy()).thenReturn(true);
            final var convergedSnapshot = createConvergedSnapshot(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            when(deviceStateRepository.findTwinSnapshot(RELAY_DEVICE_ID)).thenReturn(Optional.of(convergedSnapshot));

            final var desiredState = new DesiredDeviceState(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var event = new DesiredStateCalculatedEvent(this, desiredState);

            // When
            reconciler.onDesiredStateCalculated(event);

            // Then: No command should be sent
            Thread.sleep(DEBOUNCE_MS + 50);
            assertThat(capturedCommands).isEmpty();

            // Verify skip metric was incremented
            final var skippedCount = meterRegistry.counter("calcifer.immediate_reconciler.commands.skipped.converged").count();
            assertThat(skippedCount).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("At-Least-Once Delivery Tests")
    class AtLeastOnceDeliveryTests {

        @Test
        @DisplayName("should process all events even under concurrent load")
        void shouldProcessAllEventsUnderConcurrentLoad() throws InterruptedException {
            // Given: Infrastructure is healthy
            when(infrastructureHealthGate.isHealthy()).thenReturn(true);

            // Create 10 different devices
            final var deviceCount = 10;
            for (int i = 0; i < deviceCount; i++) {
                final var deviceId = new DeviceId("controller" + i, "relay" + i);
                final var snapshot = createNonConvergedSnapshot(deviceId, DeviceType.RELAY,
                        new RelayValue(false), new RelayValue(true));
                when(deviceStateRepository.findTwinSnapshot(deviceId)).thenReturn(Optional.of(snapshot));
            }

            // When: Send events for all devices concurrently
            final var latch = new CountDownLatch(deviceCount);
            for (int i = 0; i < deviceCount; i++) {
                final var deviceId = new DeviceId("controller" + i, "relay" + i);
                final var desiredState = new DesiredDeviceState(deviceId, DeviceType.RELAY, new RelayValue(true));
                final var event = new DesiredStateCalculatedEvent(this, desiredState);

                new Thread(() -> {
                    reconciler.onDesiredStateCalculated(event);
                    latch.countDown();
                }).start();
            }

            // Wait for all events to be submitted
            latch.await(1, TimeUnit.SECONDS);

            // Then: All commands should be sent (at-least-once)
            Thread.sleep(DEBOUNCE_MS + 200);
            assertThat(capturedCommands).hasSize(deviceCount);
        }

        @Test
        @DisplayName("should increment sent counter for each successful command")
        void shouldIncrementSentCounterForEachCommand() throws InterruptedException {
            // Given
            when(infrastructureHealthGate.isHealthy()).thenReturn(true);
            final var snapshot = createNonConvergedSnapshot(RELAY_DEVICE_ID, DeviceType.RELAY,
                    new RelayValue(false), new RelayValue(true));
            when(deviceStateRepository.findTwinSnapshot(RELAY_DEVICE_ID)).thenReturn(Optional.of(snapshot));

            final var desiredState = new DesiredDeviceState(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var event = new DesiredStateCalculatedEvent(this, desiredState);

            // When
            reconciler.onDesiredStateCalculated(event);
            Thread.sleep(DEBOUNCE_MS + 50);

            // Then
            final var sentCount = meterRegistry.counter("calcifer.immediate_reconciler.commands.sent").count();
            assertThat(sentCount).isEqualTo(1.0);
        }
    }

    // Helper methods
    private DeviceTwinSnapshot createNonConvergedSnapshot(DeviceId id, DeviceType type,
                                                          DeviceValue reported, DeviceValue desired) {
        // Constructor order: id, type, intent, reported, desired
        return new DeviceTwinSnapshot(
                id, type,
                UserIntent.now(id, type, desired),
                ReportedDeviceState.known(id, type, reported),
                new DesiredDeviceState(id, type, desired)
        );
    }

    private DeviceTwinSnapshot createConvergedSnapshot(DeviceId id, DeviceType type, DeviceValue value) {
        // Constructor order: id, type, intent, reported, desired
        return new DeviceTwinSnapshot(
                id, type,
                UserIntent.now(id, type, value),
                ReportedDeviceState.known(id, type, value),
                new DesiredDeviceState(id, type, value)
        );
    }
}

