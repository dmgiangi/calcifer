package dev.dmgiangi.core.server.infrastructure;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.FanValue;
import dev.dmgiangi.core.server.domain.model.RelayValue;
import dev.dmgiangi.core.server.domain.model.UserIntent;
import dev.dmgiangi.core.server.domain.model.event.DesiredStateCalculatedEvent;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.service.DefaultDeviceLogicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the Device Twin flow.
 * Tests the complete flow: UserIntent → DeviceLogicService → DesiredState → Event
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Device Twin Flow Integration")
class DeviceTwinFlowIntegrationTest {

    private static final DeviceId RELAY_DEVICE_ID = new DeviceId("controller1", "relay1");
    private static final DeviceId FAN_DEVICE_ID = new DeviceId("controller1", "fan1");

    @Mock
    private DeviceStateRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DefaultDeviceLogicService logicService;

    @BeforeEach
    void setUp() {
        logicService = new DefaultDeviceLogicService(repository, eventPublisher);
    }

    @Nested
    @DisplayName("RELAY Device Flow")
    class RelayDeviceFlowTests {

        @Test
        @DisplayName("should calculate and save desired state when user intent is received")
        void shouldCalculateAndSaveDesiredStateForRelay() {
            // Given: A user intent to turn on a relay
            final var intent = UserIntent.now(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var event = new UserIntentChangedEvent(this, intent);

            // Mock the repository to return a snapshot with the intent
            when(repository.findTwinSnapshot(RELAY_DEVICE_ID))
                    .thenReturn(Optional.of(new dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot(
                            RELAY_DEVICE_ID, DeviceType.RELAY, intent, null, null)));

            // When: The logic service processes the intent changed event
            logicService.onUserIntentChanged(event);

            // Then: The desired state should be saved
            final var desiredCaptor = ArgumentCaptor.forClass(
                    dev.dmgiangi.core.server.domain.model.DesiredDeviceState.class);
            verify(repository).saveDesiredState(desiredCaptor.capture());

            final var savedDesired = desiredCaptor.getValue();
            assertThat(savedDesired.id()).isEqualTo(RELAY_DEVICE_ID);
            assertThat(savedDesired.type()).isEqualTo(DeviceType.RELAY);
            assertThat(savedDesired.value()).isEqualTo(new RelayValue(true));

            // And: A DesiredStateCalculatedEvent should be published
            final var eventCaptor = ArgumentCaptor.forClass(DesiredStateCalculatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getDesiredState()).isEqualTo(savedDesired);
        }
    }

    @Nested
    @DisplayName("FAN Device Flow")
    class FanDeviceFlowTests {

        @Test
        @DisplayName("should calculate and save desired state for FAN device")
        void shouldCalculateAndSaveDesiredStateForFan() {
            // Given: A user intent to set fan speed to 128
            final var intent = UserIntent.now(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(128));
            final var event = new UserIntentChangedEvent(this, intent);

            when(repository.findTwinSnapshot(FAN_DEVICE_ID))
                    .thenReturn(Optional.of(new dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot(
                            FAN_DEVICE_ID, DeviceType.FAN, intent, null, null)));

            // When: The logic service processes the intent changed event
            logicService.onUserIntentChanged(event);

            // Then: The desired state should be saved with FanValue
            final var desiredCaptor = ArgumentCaptor.forClass(
                    dev.dmgiangi.core.server.domain.model.DesiredDeviceState.class);
            verify(repository).saveDesiredState(desiredCaptor.capture());

            final var savedDesired = desiredCaptor.getValue();
            assertThat(savedDesired.id()).isEqualTo(FAN_DEVICE_ID);
            assertThat(savedDesired.type()).isEqualTo(DeviceType.FAN);
            assertThat(savedDesired.value()).isEqualTo(new FanValue(128));

            // And: An event should be published
            verify(eventPublisher).publishEvent(any(DesiredStateCalculatedEvent.class));
        }

        @Test
        @DisplayName("should handle FAN speed 0 (off)")
        void shouldHandleFanSpeedZero() {
            final var intent = UserIntent.now(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(0));
            final var event = new UserIntentChangedEvent(this, intent);

            when(repository.findTwinSnapshot(FAN_DEVICE_ID))
                    .thenReturn(Optional.of(new dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot(
                            FAN_DEVICE_ID, DeviceType.FAN, intent, null, null)));

            logicService.onUserIntentChanged(event);

            final var desiredCaptor = ArgumentCaptor.forClass(
                    dev.dmgiangi.core.server.domain.model.DesiredDeviceState.class);
            verify(repository).saveDesiredState(desiredCaptor.capture());
            assertThat(desiredCaptor.getValue().value()).isEqualTo(new FanValue(0));
        }

        @Test
        @DisplayName("should handle FAN speed 255 (max)")
        void shouldHandleFanSpeedMax() {
            final var intent = UserIntent.now(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(255));
            final var event = new UserIntentChangedEvent(this, intent);

            when(repository.findTwinSnapshot(FAN_DEVICE_ID))
                    .thenReturn(Optional.of(new dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot(
                            FAN_DEVICE_ID, DeviceType.FAN, intent, null, null)));

            logicService.onUserIntentChanged(event);

            final var desiredCaptor = ArgumentCaptor.forClass(
                    dev.dmgiangi.core.server.domain.model.DesiredDeviceState.class);
            verify(repository).saveDesiredState(desiredCaptor.capture());
            assertThat(desiredCaptor.getValue().value()).isEqualTo(new FanValue(255));
        }
    }
}

