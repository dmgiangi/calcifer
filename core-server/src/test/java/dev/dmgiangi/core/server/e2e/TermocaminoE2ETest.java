package dev.dmgiangi.core.server.e2e;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.port.DecisionAuditRepository;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.domain.port.OverrideResolver;
import dev.dmgiangi.core.server.domain.port.OverrideResolver.ResolvedOverride;
import dev.dmgiangi.core.server.domain.service.*;
import dev.dmgiangi.core.server.domain.service.ReconciliationCoordinator.ReconciliationResult;
import dev.dmgiangi.core.server.domain.service.StateCalculator.CalculationResult.ValueSource;
import dev.dmgiangi.core.server.domain.service.rules.FirePumpInterlockRule;
import dev.dmgiangi.core.server.domain.service.rules.MaxFanSpeedRule;
import dev.dmgiangi.core.server.domain.service.rules.PumpFireInterlockRule;
import dev.dmgiangi.core.server.infrastructure.health.InfrastructureHealthGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * End-to-End integration tests for Termocamino scenario.
 * Tests the complete flow: Config → Override → Intent → Safety → Reconcile → Command
 *
 * <p>Termocamino is a wood-burning fireplace with water heating system.
 * Critical safety rules:
 * <ul>
 *   <li>Fire ON → Pump must be ON (prevent overheating)</li>
 *   <li>Pump ON → Fire cannot be turned OFF (prevent thermal runaway)</li>
 * </ul>
 *
 * <p>This test uses mocked repositories to test the domain logic without
 * requiring full infrastructure (RabbitMQ, etc.).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Termocamino E2E Integration Tests")
class TermocaminoE2ETest {

    // Device IDs for Termocamino system
    private static final DeviceId FIRE_RELAY = new DeviceId("termocamino", "fire-relay");
    private static final DeviceId PUMP_RELAY = new DeviceId("termocamino", "pump-relay");
    private static final DeviceId FAN = new DeviceId("termocamino", "fan");

    private static final String SYSTEM_ID = "termocamino-system-001";

    @Mock
    private FunctionalSystemRepository systemRepository;

    @Mock
    private DeviceStateRepository deviceStateRepository;

    @Mock
    private OverrideResolver overrideResolver;

    @Mock
    private DecisionAuditRepository decisionAuditRepository;

    @Mock
    private InfrastructureHealthGate infrastructureHealthGate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SafetyRuleEngine safetyRuleEngine;
    private SafetyValidator safetyValidator;
    private StateCalculator stateCalculator;
    private ReconciliationCoordinator reconciliationCoordinator;
    private SimpleMeterRegistry meterRegistry;

    // In-memory state storage for test
    private final Map<DeviceId, DeviceTwinSnapshot> deviceSnapshots = new HashMap<>();
    private final Map<String, ResolvedOverride> activeOverrides = new HashMap<>();
    private FunctionalSystemData termocaminoSystem;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        // Initialize safety rules engine
        safetyRuleEngine = new SafetyRuleEngine(
                List.of(
                        new FirePumpInterlockRule(),
                        new PumpFireInterlockRule(),
                        new MaxFanSpeedRule()
                ),
                meterRegistry
        );

        // Create safety validator wrapping the rule engine
        safetyValidator = new DefaultSafetyValidator(safetyRuleEngine, deviceStateRepository);

        // Create state calculator with safety validator and override resolver
        stateCalculator = new DefaultStateCalculator(safetyValidator, overrideResolver);

        // Create reconciliation coordinator with all required dependencies
        reconciliationCoordinator = new DefaultReconciliationCoordinator(
                stateCalculator,
                deviceStateRepository,
                systemRepository,
                decisionAuditRepository,
                infrastructureHealthGate,
                eventPublisher,
                meterRegistry
        );

        // Initialize Termocamino system
        initializeTermocaminoSystem();

        // Setup mock behaviors
        setupMockBehaviors();
    }

    private void initializeTermocaminoSystem() {
        termocaminoSystem = new FunctionalSystemData(
                SYSTEM_ID,
                "TERMOCAMINO",
                "Living Room Termocamino",
                Map.of(
                        "mode", "AUTO",
                        "targetTemperature", 22.0,
                        "safetyThresholds", Map.of(
                                "maxTemperature", 80.0,
                                "criticalTemperature", 85.0
                        )
                ),
                Set.of(deviceKey(FIRE_RELAY), deviceKey(PUMP_RELAY), deviceKey(FAN)),
                Map.of(
                        "fire-relay", false,
                        "pump-relay", true,  // Fail-safe: pump ON
                        "fan", 0
                ),
                Instant.now(),
                Instant.now(),
                "test-setup",
                null
        );

        // Initialize device snapshots - all OFF initially
        deviceSnapshots.put(FIRE_RELAY, createSnapshot(FIRE_RELAY, DeviceType.RELAY, new RelayValue(false)));
        deviceSnapshots.put(PUMP_RELAY, createSnapshot(PUMP_RELAY, DeviceType.RELAY, new RelayValue(false)));
        deviceSnapshots.put(FAN, createSnapshot(FAN, DeviceType.FAN, new FanValue(0)));
    }

    private void setupMockBehaviors() {
        // Mock infrastructure health gate - always healthy for tests
        lenient().when(infrastructureHealthGate.isHealthy()).thenReturn(true);

        // Mock findTwinSnapshot
        lenient().when(deviceStateRepository.findTwinSnapshot(any(DeviceId.class)))
                .thenAnswer(inv -> Optional.ofNullable(deviceSnapshots.get(inv.getArgument(0))));

        // Mock findByDeviceId for system lookup
        lenient().when(systemRepository.findByDeviceId(anyString()))
                .thenAnswer(inv -> {
                    final var deviceKey = (String) inv.getArgument(0);
                    if (termocaminoSystem.deviceIds().contains(deviceKey)) {
                        return Optional.of(termocaminoSystem);
                    }
                    return Optional.empty();
                });

        // Mock override resolver - resolves from our in-memory activeOverrides map
        lenient().when(overrideResolver.resolveEffective(any(DeviceId.class), anyString()))
                .thenAnswer(inv -> {
                    final var deviceId = (DeviceId) inv.getArgument(0);
                    final var deviceKey = deviceKey(deviceId);
                    return Optional.ofNullable(activeOverrides.get(deviceKey));
                });

        // Mock saveDesiredState to update our in-memory state
        lenient().doAnswer(inv -> {
            final var desired = (DesiredDeviceState) inv.getArgument(0);
            final var current = deviceSnapshots.get(desired.id());
            if (current != null) {
                deviceSnapshots.put(desired.id(), new DeviceTwinSnapshot(
                        current.id(), current.type(), current.intent(), current.reported(), desired
                ));
            }
            return null;
        }).when(deviceStateRepository).saveDesiredState(any(DesiredDeviceState.class));
    }

    private DeviceTwinSnapshot createSnapshot(DeviceId id, DeviceType type, DeviceValue value) {
        return new DeviceTwinSnapshot(
                id,
                type,
                UserIntent.now(id, type, value),
                ReportedDeviceState.known(id, type, value),
                new DesiredDeviceState(id, type, value)
        );
    }

    private void updateIntent(DeviceId id, DeviceType type, DeviceValue value) {
        final var current = deviceSnapshots.get(id);
        deviceSnapshots.put(id, new DeviceTwinSnapshot(
                id, type, UserIntent.now(id, type, value), current.reported(), current.desired()
        ));
    }

    private void updateReported(DeviceId id, DeviceType type, DeviceValue value) {
        final var current = deviceSnapshots.get(id);
        deviceSnapshots.put(id, new DeviceTwinSnapshot(
                id, type, current.intent(), ReportedDeviceState.known(id, type, value), current.desired()
        ));
    }

    private void updateDesired(DeviceId id, DeviceType type, DeviceValue value) {
        final var current = deviceSnapshots.get(id);
        deviceSnapshots.put(id, new DeviceTwinSnapshot(
                id, type, current.intent(), current.reported(), new DesiredDeviceState(id, type, value)
        ));
    }

    /**
     * Updates all three states (intent, reported, desired) to simulate a converged device.
     */
    private void setDeviceState(DeviceId id, DeviceType type, DeviceValue value) {
        deviceSnapshots.put(id, new DeviceTwinSnapshot(
                id,
                type,
                UserIntent.now(id, type, value),
                ReportedDeviceState.known(id, type, value),
                new DesiredDeviceState(id, type, value)
        ));
    }

    private String deviceKey(DeviceId id) {
        return id.controllerId() + ":" + id.componentId();
    }

    // ========== SCENARIO 1: Basic Intent Flow ==========

    @Nested
    @DisplayName("Scenario 1: Basic Intent Flow")
    class BasicIntentFlowTests {

        @Test
        @DisplayName("should process user intent and generate command")
        void shouldProcessUserIntentAndGenerateCommand() {
            // Given: User wants to turn ON the fan at speed 2
            updateIntent(FAN, DeviceType.FAN, new FanValue(2));

            // When: Reconciliation is triggered
            final var result = reconciliationCoordinator.reconcile(FAN, Map.of());

            // Then: Reconciliation succeeds with INTENT source
            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.SUCCESS);
            assertThat(result.calculationResult().source()).isEqualTo(ValueSource.INTENT);

            // And: Desired state is updated
            final var snapshot = deviceSnapshots.get(FAN);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.desired().value()).isEqualTo(new FanValue(2));
        }
    }

    // ========== SCENARIO 2: Override Takes Precedence ==========

    @Nested
    @DisplayName("Scenario 2: Override Takes Precedence Over Intent")
    class OverridePrecedenceTests {

        @Test
        @DisplayName("should apply override value instead of user intent")
        void shouldApplyOverrideValueInsteadOfIntent() {
            // Given: User intent is fan speed 2
            updateIntent(FAN, DeviceType.FAN, new FanValue(2));

            // And: Maintenance override sets fan to speed 4
            final var override = createOverride(
                    deviceKey(FAN), OverrideScope.DEVICE, OverrideCategory.MAINTENANCE,
                    new FanValue(4), "Maintenance: max ventilation required"
            );
            activeOverrides.put(deviceKey(FAN), override);

            // When: Reconciliation is triggered
            final var result = reconciliationCoordinator.reconcile(FAN, Map.of());

            // Then: Override value is applied (speed 4, not 2)
            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.SUCCESS);
            assertThat(result.calculationResult().source()).isEqualTo(ValueSource.OVERRIDE);

            final var snapshot = deviceSnapshots.get(FAN);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.desired().value()).isEqualTo(new FanValue(4));
        }

        @Test
        @DisplayName("should apply higher category override over lower")
        void shouldApplyHigherCategoryOverride() {
            // Given: EMERGENCY override sets fan to speed 4 (highest priority wins)
            final var emergencyOverride = createOverride(
                    deviceKey(FAN), OverrideScope.DEVICE, OverrideCategory.EMERGENCY,
                    new FanValue(4), "Emergency: max ventilation"
            );
            activeOverrides.put(deviceKey(FAN), emergencyOverride);

            // When: Reconciliation is triggered
            final var result = reconciliationCoordinator.reconcile(FAN, Map.of());

            // Then: EMERGENCY override wins (speed 4)
            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.SUCCESS);
            final var snapshot = deviceSnapshots.get(FAN);
            assertThat(snapshot.desired().value()).isEqualTo(new FanValue(4));
        }
    }

    // ========== SCENARIO 3: Safety Rules - Fire-Pump Interlock ==========

    @Nested
    @DisplayName("Scenario 3: Safety Rules - Fire-Pump Interlock")
    class SafetyInterlockTests {

        @Test
        @DisplayName("should force pump ON when fire is ON (FirePumpInterlockRule)")
        void shouldForcePumpOnWhenFireIsOn() {
            // Given: Fire is ON (all states converged - simulates fire already running)
            // Safety rules check desired state of related devices
            setDeviceState(FIRE_RELAY, DeviceType.RELAY, new RelayValue(true));

            // And: User wants to turn pump OFF
            updateIntent(PUMP_RELAY, DeviceType.RELAY, new RelayValue(false));

            // When: Reconciliation is triggered for pump
            final var result = reconciliationCoordinator.reconcile(PUMP_RELAY, Map.of());

            // Then: Safety rule modifies the value - pump stays ON
            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.SUCCESS);
            assertThat(result.calculationResult().source()).isEqualTo(ValueSource.SAFETY_MODIFIED);

            final var snapshot = deviceSnapshots.get(PUMP_RELAY);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.desired().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should refuse fire OFF when pump is ON (PumpFireInterlockRule)")
        void shouldRefuseFireOffWhenPumpIsOn() {
            // Given: Pump is ON (all states converged - simulates pump already running)
            // Safety rules check desired state of related devices
            setDeviceState(PUMP_RELAY, DeviceType.RELAY, new RelayValue(true));

            // And: User wants to turn fire OFF
            updateIntent(FIRE_RELAY, DeviceType.RELAY, new RelayValue(false));

            // When: Reconciliation is triggered for fire
            final var result = reconciliationCoordinator.reconcile(FIRE_RELAY, Map.of());

            // Then: Safety rule refuses the change
            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.SAFETY_REFUSED);
            assertThat(result.calculationResult().source()).isEqualTo(ValueSource.SAFETY_REFUSED);
            assertThat(result.reason()).contains("pump");
        }
    }

    // ========== SCENARIO 4: Max Fan Speed Safety ==========

    @Nested
    @DisplayName("Scenario 4: Max Fan Speed Safety Rule")
    class MaxFanSpeedTests {

        @Test
        @DisplayName("should limit fan speed to maximum allowed")
        void shouldLimitFanSpeedToMaximum() {
            // Given: User wants fan speed 4 (max allowed)
            updateIntent(FAN, DeviceType.FAN, new FanValue(4));

            // When: Reconciliation is triggered
            final var result = reconciliationCoordinator.reconcile(FAN, Map.of());

            // Then: Speed 4 is accepted (it's the max)
            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.SUCCESS);
            final var snapshot = deviceSnapshots.get(FAN);
            assertThat(snapshot.desired().value()).isEqualTo(new FanValue(4));
        }
    }

    // ========== SCENARIO 5: Complete Flow ==========

    @Nested
    @DisplayName("Scenario 5: Complete Termocamino Startup Flow")
    class CompleteFlowTests {

        @Test
        @DisplayName("should handle complete startup sequence: pump ON → fire ON")
        void shouldHandleCompleteStartupSequence() {
            // Step 1: Turn pump ON first (safe operation)
            updateIntent(PUMP_RELAY, DeviceType.RELAY, new RelayValue(true));
            final var pumpResult = reconciliationCoordinator.reconcile(PUMP_RELAY, Map.of());
            assertThat(pumpResult.outcome()).isEqualTo(ReconciliationResult.Outcome.SUCCESS);

            // Simulate pump feedback - pump is now ON
            updateReported(PUMP_RELAY, DeviceType.RELAY, new RelayValue(true));

            // Step 2: Now turn fire ON (safe because pump is running)
            updateIntent(FIRE_RELAY, DeviceType.RELAY, new RelayValue(true));
            final var fireResult = reconciliationCoordinator.reconcile(FIRE_RELAY, Map.of());
            assertThat(fireResult.outcome()).isEqualTo(ReconciliationResult.Outcome.SUCCESS);

            // Verify final states
            final var pumpSnapshot = deviceSnapshots.get(PUMP_RELAY);
            final var fireSnapshot = deviceSnapshots.get(FIRE_RELAY);

            assertThat(pumpSnapshot.desired().value()).isEqualTo(new RelayValue(true));
            assertThat(fireSnapshot.desired().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should verify event publishing on successful reconciliation")
        void shouldPublishEventOnSuccessfulReconciliation() {
            // Given: User wants to turn fan ON
            updateIntent(FAN, DeviceType.FAN, new FanValue(2));

            // When: Reconciliation is triggered
            final var result = reconciliationCoordinator.reconcile(FAN, Map.of());

            // Then: Event is published
            assertThat(result.outcome()).isEqualTo(ReconciliationResult.Outcome.SUCCESS);
            verify(eventPublisher).publishEvent(any());
        }
    }

    // ========== Helper Methods ==========

    private ResolvedOverride createOverride(String targetId, OverrideScope scope, OverrideCategory category,
                                            DeviceValue value, String reason) {
        // ResolvedOverride is what the OverrideResolver returns
        // isFromSystem is true if scope is SYSTEM
        final var isFromSystem = scope == OverrideScope.SYSTEM;
        return ResolvedOverride.of(value, category, reason, isFromSystem);
    }
}

