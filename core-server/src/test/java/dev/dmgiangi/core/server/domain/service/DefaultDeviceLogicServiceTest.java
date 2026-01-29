package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.event.ReportedStateChangedEvent;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.service.ReconciliationCoordinator.ReconciliationResult;
import dev.dmgiangi.core.server.domain.service.StateCalculator.CalculationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("DefaultDeviceLogicService")
@ExtendWith(MockitoExtension.class)
class DefaultDeviceLogicServiceTest {

    private static final DeviceId DEVICE_ID = new DeviceId("controller-1", "relay-1");
    private static final Instant TIMESTAMP = Instant.parse("2026-01-25T10:00:00Z");

    @Mock
    private ReconciliationCoordinator reconciliationCoordinator;

    @Mock
    private StateCalculator stateCalculator;

    private DefaultDeviceLogicService service;

    @BeforeEach
    void setUp() {
        service = new DefaultDeviceLogicService(reconciliationCoordinator, stateCalculator);
    }

    @Nested
    @DisplayName("calculateDesired()")
    class CalculateDesiredTests {

        @Test
        @DisplayName("should return DesiredDeviceState when intent exists")
        void shouldReturnDesiredWhenIntentExists() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);
            final var expectedDesired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));

            when(stateCalculator.calculate(eq(snapshot), isNull(), any(Map.class)))
                    .thenReturn(Optional.of(expectedDesired));

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

            when(stateCalculator.calculate(eq(snapshot), isNull(), any(Map.class)))
                    .thenReturn(Optional.empty());

            final var result = service.calculateDesired(snapshot);

            assertNull(result);
        }

        @Test
        @DisplayName("should passthrough FanValue")
        void shouldPassthroughFanValue() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.FAN, new FanValue(3), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.FAN, intent, null, null);
            final var expectedDesired = new DesiredDeviceState(DEVICE_ID, DeviceType.FAN, new FanValue(3));

            when(stateCalculator.calculate(eq(snapshot), isNull(), any(Map.class)))
                    .thenReturn(Optional.of(expectedDesired));

            final var result = service.calculateDesired(snapshot);

            assertNotNull(result);
            assertEquals(new FanValue(3), result.value());
        }

        @Test
        @DisplayName("should return DesiredDeviceState when reported is null (cold start)")
        void shouldReturnDesiredWhenReportedIsNull() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);
            final var expectedDesired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));

            when(stateCalculator.calculate(eq(snapshot), isNull(), any(Map.class)))
                    .thenReturn(Optional.of(expectedDesired));

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
            final var expectedDesired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));

            when(stateCalculator.calculate(eq(snapshot), isNull(), any(Map.class)))
                    .thenReturn(Optional.of(expectedDesired));

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
            final var expectedDesired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));

            when(stateCalculator.calculate(eq(snapshot), isNull(), any(Map.class)))
                    .thenReturn(Optional.of(expectedDesired));

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
        @DisplayName("should delegate to ReconciliationCoordinator when intent exists")
        void shouldDelegateToCoordinatorWhenIntentExists() {
            final var expectedDesired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var calcResult = CalculationResult.fromIntent(expectedDesired);
            final var reconcileResult = ReconciliationResult.success(expectedDesired, calcResult);

            when(reconciliationCoordinator.reconcile(eq(DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            service.recalculateDesiredState(DEVICE_ID);

            verify(reconciliationCoordinator).reconcile(eq(DEVICE_ID), any(Map.class));
        }

        @Test
        @DisplayName("should handle device not found from coordinator")
        void shouldHandleDeviceNotFound() {
            final var reconcileResult = ReconciliationResult.deviceNotFound(DEVICE_ID);

            when(reconciliationCoordinator.reconcile(eq(DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            service.recalculateDesiredState(DEVICE_ID);

            verify(reconciliationCoordinator).reconcile(eq(DEVICE_ID), any(Map.class));
        }

        @Test
        @DisplayName("should handle no change from coordinator when intent is null")
        void shouldHandleNoChangeWhenIntentIsNull() {
            final var calcResult = CalculationResult.noValue("No user intent available");
            final var reconcileResult = ReconciliationResult.noChange(calcResult, "No user intent available");

            when(reconciliationCoordinator.reconcile(eq(DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            service.recalculateDesiredState(DEVICE_ID);

            verify(reconciliationCoordinator).reconcile(eq(DEVICE_ID), any(Map.class));
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
            final var expectedDesired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var calcResult = CalculationResult.fromIntent(expectedDesired);
            final var reconcileResult = ReconciliationResult.success(expectedDesired, calcResult);

            when(reconciliationCoordinator.reconcile(eq(DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            service.onUserIntentChanged(event);

            verify(reconciliationCoordinator).reconcile(eq(DEVICE_ID), any(Map.class));
        }

        @Test
        @DisplayName("onReportedStateChanged should trigger recalculation")
        void onReportedStateChangedShouldTriggerRecalculation() {
            final var reportedState = ReportedDeviceState.known(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var event = new ReportedStateChangedEvent(this, reportedState);
            final var reconcileResult = ReconciliationResult.deviceNotFound(DEVICE_ID);

            when(reconciliationCoordinator.reconcile(eq(DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            service.onReportedStateChanged(event);

            verify(reconciliationCoordinator).reconcile(eq(DEVICE_ID), any(Map.class));
        }
    }
}

