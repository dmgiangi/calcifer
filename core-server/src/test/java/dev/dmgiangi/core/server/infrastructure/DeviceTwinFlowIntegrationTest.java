package dev.dmgiangi.core.server.infrastructure;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.service.DefaultDeviceLogicService;
import dev.dmgiangi.core.server.domain.service.ReconciliationCoordinator;
import dev.dmgiangi.core.server.domain.service.ReconciliationCoordinator.ReconciliationResult;
import dev.dmgiangi.core.server.domain.service.StateCalculator;
import dev.dmgiangi.core.server.domain.service.StateCalculator.CalculationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the Device Twin flow.
 * Tests the complete flow: UserIntent → DeviceLogicService → ReconciliationCoordinator
 *
 * <p>After Phase 3 refactoring, DefaultDeviceLogicService delegates to ReconciliationCoordinator
 * for the full reconciliation flow (load, calculate, persist, publish).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Device Twin Flow Integration")
class DeviceTwinFlowIntegrationTest {

    private static final DeviceId RELAY_DEVICE_ID = new DeviceId("controller1", "relay1");
    private static final DeviceId FAN_DEVICE_ID = new DeviceId("controller1", "fan1");

    @Mock
    private ReconciliationCoordinator reconciliationCoordinator;

    @Mock
    private StateCalculator stateCalculator;

    private DefaultDeviceLogicService logicService;

    @BeforeEach
    void setUp() {
        logicService = new DefaultDeviceLogicService(reconciliationCoordinator, stateCalculator);
    }

    @Nested
    @DisplayName("RELAY Device Flow")
    class RelayDeviceFlowTests {

        @Test
        @DisplayName("should delegate to ReconciliationCoordinator when user intent is received")
        void shouldDelegateToCoordinatorForRelay() {
            // Given: A user intent to turn on a relay
            final var intent = UserIntent.now(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var event = new UserIntentChangedEvent(this, intent);
            final var expectedDesired = new DesiredDeviceState(RELAY_DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var calcResult = CalculationResult.fromIntent(expectedDesired);
            final var reconcileResult = ReconciliationResult.success(expectedDesired, calcResult);

            when(reconciliationCoordinator.reconcile(eq(RELAY_DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            // When: The logic service processes the intent changed event
            logicService.onUserIntentChanged(event);

            // Then: The coordinator should be called
            verify(reconciliationCoordinator).reconcile(eq(RELAY_DEVICE_ID), any(Map.class));
        }
    }

    @Nested
    @DisplayName("FAN Device Flow")
    class FanDeviceFlowTests {

        @Test
        @DisplayName("should delegate to ReconciliationCoordinator for FAN device")
        void shouldDelegateToCoordinatorForFan() {
            // Given: A user intent to set fan speed to 2
            final var intent = UserIntent.now(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(2));
            final var event = new UserIntentChangedEvent(this, intent);
            final var expectedDesired = new DesiredDeviceState(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(2));
            final var calcResult = CalculationResult.fromIntent(expectedDesired);
            final var reconcileResult = ReconciliationResult.success(expectedDesired, calcResult);

            when(reconciliationCoordinator.reconcile(eq(FAN_DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            // When: The logic service processes the intent changed event
            logicService.onUserIntentChanged(event);

            // Then: The coordinator should be called
            verify(reconciliationCoordinator).reconcile(eq(FAN_DEVICE_ID), any(Map.class));
        }

        @Test
        @DisplayName("should handle FAN speed 0 (off)")
        void shouldHandleFanSpeedZero() {
            final var intent = UserIntent.now(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(0));
            final var event = new UserIntentChangedEvent(this, intent);
            final var expectedDesired = new DesiredDeviceState(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(0));
            final var calcResult = CalculationResult.fromIntent(expectedDesired);
            final var reconcileResult = ReconciliationResult.success(expectedDesired, calcResult);

            when(reconciliationCoordinator.reconcile(eq(FAN_DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            logicService.onUserIntentChanged(event);

            verify(reconciliationCoordinator).reconcile(eq(FAN_DEVICE_ID), any(Map.class));
        }

        @Test
        @DisplayName("should handle FAN speed 4 (max)")
        void shouldHandleFanSpeedMax() {
            final var intent = UserIntent.now(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(4));
            final var event = new UserIntentChangedEvent(this, intent);
            final var expectedDesired = new DesiredDeviceState(FAN_DEVICE_ID, DeviceType.FAN, new FanValue(4));
            final var calcResult = CalculationResult.fromIntent(expectedDesired);
            final var reconcileResult = ReconciliationResult.success(expectedDesired, calcResult);

            when(reconciliationCoordinator.reconcile(eq(FAN_DEVICE_ID), any(Map.class)))
                    .thenReturn(reconcileResult);

            logicService.onUserIntentChanged(event);

            verify(reconciliationCoordinator).reconcile(eq(FAN_DEVICE_ID), any(Map.class));
        }
    }
}

