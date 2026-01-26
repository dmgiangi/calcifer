package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.event.DesiredStateCalculatedEvent;
import dev.dmgiangi.core.server.domain.model.event.ReportedStateChangedEvent;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DefaultDeviceLogicService")
@ExtendWith(MockitoExtension.class)
class DefaultDeviceLogicServiceTest {

    private static final DeviceId DEVICE_ID = new DeviceId("controller-1", "relay-1");
    private static final Instant TIMESTAMP = Instant.parse("2026-01-25T10:00:00Z");

    @Mock
    private DeviceStateRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DefaultDeviceLogicService service;

    @BeforeEach
    void setUp() {
        service = new DefaultDeviceLogicService(repository, eventPublisher);
    }

    @Nested
    @DisplayName("calculateDesired()")
    class CalculateDesiredTests {

        @Test
        @DisplayName("should return DesiredDeviceState when intent exists")
        void shouldReturnDesiredWhenIntentExists() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);

            final var result = service.calculateDesired(snapshot);

            assertNotNull(result);
            assertEquals(DEVICE_ID, result.id());
            assertEquals(DeviceType.RELAY, result.type());
            assertEquals(new RelayValue(true), result.value());
        }

        @Test
        @DisplayName("should return null when intent is null")
        void shouldReturnNullWhenIntentIsNull() {
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, null);

            final var result = service.calculateDesired(snapshot);

            assertNull(result);
        }

        @Test
        @DisplayName("should passthrough FanValue")
        void shouldPassthroughFanValue() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.FAN, new FanValue(128), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.FAN, intent, null, null);

            final var result = service.calculateDesired(snapshot);

            assertNotNull(result);
            assertEquals(new FanValue(128), result.value());
        }

        @Test
        @DisplayName("should return DesiredDeviceState when reported is null (cold start)")
        void shouldReturnDesiredWhenReportedIsNull() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);

            final var result = service.calculateDesired(snapshot);

            assertNotNull(result);
            assertEquals(DEVICE_ID, result.id());
            assertEquals(DeviceType.RELAY, result.type());
            assertEquals(new RelayValue(true), result.value());
        }

        @Test
        @DisplayName("should return DesiredDeviceState when reported is unknown (cold start)")
        void shouldReturnDesiredWhenReportedIsUnknown() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var unknownReported = ReportedDeviceState.unknown(DEVICE_ID, DeviceType.RELAY);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, unknownReported, null);

            final var result = service.calculateDesired(snapshot);

            assertNotNull(result);
            assertEquals(DEVICE_ID, result.id());
            assertEquals(DeviceType.RELAY, result.type());
            assertEquals(new RelayValue(true), result.value());
        }

        @Test
        @DisplayName("should return DesiredDeviceState when reported is known (normal operation)")
        void shouldReturnDesiredWhenReportedIsKnown() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var knownReported = ReportedDeviceState.known(DEVICE_ID, DeviceType.RELAY, new RelayValue(false));
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, knownReported, null);

            final var result = service.calculateDesired(snapshot);

            assertNotNull(result);
            assertEquals(DEVICE_ID, result.id());
            assertEquals(DeviceType.RELAY, result.type());
            assertEquals(new RelayValue(true), result.value());
        }
    }

    @Nested
    @DisplayName("recalculateDesiredState()")
    class RecalculateDesiredStateTests {

        @Test
        @DisplayName("should save and publish event when intent exists")
        void shouldSaveAndPublishWhenIntentExists() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);
            when(repository.findTwinSnapshot(DEVICE_ID)).thenReturn(Optional.of(snapshot));

            service.recalculateDesiredState(DEVICE_ID);

            final var desiredCaptor = ArgumentCaptor.forClass(DesiredDeviceState.class);
            verify(repository).saveDesiredState(desiredCaptor.capture());
            final var savedDesired = desiredCaptor.getValue();
            assertEquals(DEVICE_ID, savedDesired.id());
            assertEquals(new RelayValue(true), savedDesired.value());

            final var eventCaptor = ArgumentCaptor.forClass(DesiredStateCalculatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertEquals(savedDesired, eventCaptor.getValue().getDesiredState());
        }

        @Test
        @DisplayName("should not save or publish when snapshot not found")
        void shouldNotSaveWhenSnapshotNotFound() {
            when(repository.findTwinSnapshot(DEVICE_ID)).thenReturn(Optional.empty());

            service.recalculateDesiredState(DEVICE_ID);

            verify(repository, never()).saveDesiredState(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("should not save or publish when intent is null")
        void shouldNotSaveWhenIntentIsNull() {
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, null);
            when(repository.findTwinSnapshot(DEVICE_ID)).thenReturn(Optional.of(snapshot));

            service.recalculateDesiredState(DEVICE_ID);

            verify(repository, never()).saveDesiredState(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("Event Listeners")
    class EventListenerTests {

        @Test
        @DisplayName("onUserIntentChanged should trigger recalculation")
        void onUserIntentChangedShouldTriggerRecalculation() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var event = new UserIntentChangedEvent(this, intent);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);
            when(repository.findTwinSnapshot(DEVICE_ID)).thenReturn(Optional.of(snapshot));

            service.onUserIntentChanged(event);

            verify(repository).findTwinSnapshot(DEVICE_ID);
            verify(repository).saveDesiredState(any());
        }

        @Test
        @DisplayName("onReportedStateChanged should trigger recalculation")
        void onReportedStateChangedShouldTriggerRecalculation() {
            final var reportedState = ReportedDeviceState.known(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var event = new ReportedStateChangedEvent(this, reportedState);
            when(repository.findTwinSnapshot(DEVICE_ID)).thenReturn(Optional.empty());

            service.onReportedStateChanged(event);

            verify(repository).findTwinSnapshot(DEVICE_ID);
        }
    }
}

