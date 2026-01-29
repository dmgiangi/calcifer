package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.safety.RuleCategory;
import dev.dmgiangi.core.server.domain.model.safety.SafetyContext;
import dev.dmgiangi.core.server.domain.model.safety.SafetyRule;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult;
import dev.dmgiangi.core.server.domain.service.rules.FirePumpInterlockRule;
import dev.dmgiangi.core.server.domain.service.rules.MaxFanSpeedRule;
import dev.dmgiangi.core.server.domain.service.rules.PumpFireInterlockRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SafetyRuleEngine.
 * Tests rule evaluation, combinations, and conflict resolution.
 */
@DisplayName("SafetyRuleEngine")
class SafetyRuleEngineTest {

    private static final DeviceId FIRE_RELAY = new DeviceId("termocamino", "fire-relay");
    private static final DeviceId PUMP_RELAY = new DeviceId("termocamino", "pump-relay");
    private static final DeviceId FAN_DEVICE = new DeviceId("hvac", "fan1");

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Nested
    @DisplayName("MaxFanSpeedRule")
    class MaxFanSpeedRuleTests {

        private SafetyRuleEngine engine;

        @BeforeEach
        void setUp() {
            engine = new SafetyRuleEngine(List.of(new MaxFanSpeedRule()), meterRegistry);
        }

        @Test
        @DisplayName("should accept fan speed within limit")
        void shouldAcceptFanSpeedWithinLimit() {
            final var context = SafetyContext.builder()
                    .deviceId(FAN_DEVICE)
                    .deviceType(DeviceType.FAN)
                    .proposedValue(new FanValue(3))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
            assertThat(result.evaluatedRules()).contains("MAX_FAN_SPEED");
        }

        @Test
        @DisplayName("should accept maximum fan speed (4)")
        void shouldAcceptMaximumFanSpeed() {
            final var context = SafetyContext.builder()
                    .deviceId(FAN_DEVICE)
                    .deviceType(DeviceType.FAN)
                    .proposedValue(new FanValue(4))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
        }

        @Test
        @DisplayName("should accept fan speed at zero (OFF)")
        void shouldAcceptFanSpeedAtZero() {
            final var context = SafetyContext.builder()
                    .deviceId(FAN_DEVICE)
                    .deviceType(DeviceType.FAN)
                    .proposedValue(new FanValue(0))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
        }

        @Test
        @DisplayName("should not apply to RELAY devices")
        void shouldNotApplyToRelayDevices() {
            final var context = SafetyContext.builder()
                    .deviceId(FIRE_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(true))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
            assertThat(result.evaluatedRules()).isEmpty();
        }
    }

    @Nested
    @DisplayName("PumpFireInterlockRule")
    class PumpFireInterlockRuleTests {

        private SafetyRuleEngine engine;

        @BeforeEach
        void setUp() {
            engine = new SafetyRuleEngine(List.of(new PumpFireInterlockRule()), meterRegistry);
        }

        @Test
        @DisplayName("should accept turning fire ON regardless of pump state")
        void shouldAcceptTurningFireOn() {
            final var context = SafetyContext.builder()
                    .deviceId(FIRE_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(true))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
        }

        @Test
        @DisplayName("should accept turning fire OFF when pump is OFF")
        void shouldAcceptTurningFireOffWhenPumpOff() {
            // Create pump snapshot with pump OFF
            final var pumpDesired = new DesiredDeviceState(PUMP_RELAY, DeviceType.RELAY, new RelayValue(false));
            final var pumpSnapshot = new DeviceTwinSnapshot(PUMP_RELAY, DeviceType.RELAY, null, null, pumpDesired);

            final var context = SafetyContext.builder()
                    .deviceId(FIRE_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(false))
                    .relatedDeviceStates(Map.of(PUMP_RELAY, pumpSnapshot))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
        }

        @Test
        @DisplayName("should REFUSE turning fire OFF when pump is ON")
        void shouldRefuseTurningFireOffWhenPumpOn() {
            // Create pump snapshot with pump ON
            final var pumpDesired = new DesiredDeviceState(PUMP_RELAY, DeviceType.RELAY, new RelayValue(true));
            final var pumpSnapshot = new DeviceTwinSnapshot(PUMP_RELAY, DeviceType.RELAY, null, null, pumpDesired);

            final var context = SafetyContext.builder()
                    .deviceId(FIRE_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(false))
                    .relatedDeviceStates(Map.of(PUMP_RELAY, pumpSnapshot))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isRefused()).isTrue();
            assertThat(result.getBlockingRuleId()).contains("PUMP_FIRE_INTERLOCK");
            assertThat(result.getRefusalReason()).isPresent();
            assertThat(result.getRefusalReason().get()).contains("pump");
        }

        @Test
        @DisplayName("should accept when no pump found in related devices")
        void shouldAcceptWhenNoPumpFound() {
            final var context = SafetyContext.builder()
                    .deviceId(FIRE_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(false))
                    .relatedDeviceStates(Map.of())
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
        }
    }

    @Nested
    @DisplayName("FirePumpInterlockRule")
    class FirePumpInterlockRuleTests {

        private SafetyRuleEngine engine;

        @BeforeEach
        void setUp() {
            engine = new SafetyRuleEngine(List.of(new FirePumpInterlockRule()), meterRegistry);
        }

        @Test
        @DisplayName("should accept turning pump ON")
        void shouldAcceptTurningPumpOn() {
            final var context = SafetyContext.builder()
                    .deviceId(PUMP_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(true))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
        }

        @Test
        @DisplayName("should accept turning pump OFF when fire is OFF")
        void shouldAcceptTurningPumpOffWhenFireOff() {
            final var fireDesired = new DesiredDeviceState(FIRE_RELAY, DeviceType.RELAY, new RelayValue(false));
            final var fireSnapshot = new DeviceTwinSnapshot(FIRE_RELAY, DeviceType.RELAY, null, null, fireDesired);

            final var context = SafetyContext.builder()
                    .deviceId(PUMP_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(false))
                    .relatedDeviceStates(Map.of(FIRE_RELAY, fireSnapshot))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
        }

        @Test
        @DisplayName("should MODIFY pump OFF to pump ON when fire is ON")
        void shouldModifyPumpOffToOnWhenFireOn() {
            final var fireDesired = new DesiredDeviceState(FIRE_RELAY, DeviceType.RELAY, new RelayValue(true));
            final var fireSnapshot = new DeviceTwinSnapshot(FIRE_RELAY, DeviceType.RELAY, null, null, fireDesired);

            final var context = SafetyContext.builder()
                    .deviceId(PUMP_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(false))
                    .relatedDeviceStates(Map.of(FIRE_RELAY, fireSnapshot))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isModified()).isTrue();
            assertThat(result.originalValue()).isEqualTo(new RelayValue(false));
            assertThat(result.finalValue()).isEqualTo(new RelayValue(true));
            assertThat(result.evaluatedRules()).contains("FIRE_PUMP_INTERLOCK");
        }
    }

    @Nested
    @DisplayName("Multiple Rules Combination")
    class MultipleRulesCombinationTests {

        private SafetyRuleEngine engine;

        @BeforeEach
        void setUp() {
            engine = new SafetyRuleEngine(
                    List.of(
                            new PumpFireInterlockRule(),
                            new FirePumpInterlockRule(),
                            new MaxFanSpeedRule()
                    ),
                    meterRegistry
            );
        }

        @Test
        @DisplayName("should have correct rule count")
        void shouldHaveCorrectRuleCount() {
            assertThat(engine.getRuleCount()).isEqualTo(3);
            assertThat(engine.getHardcodedRuleCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should find rule by ID")
        void shouldFindRuleById() {
            final var rule = engine.findRuleById("MAX_FAN_SPEED");

            assertThat(rule).isPresent();
            assertThat(rule.get().getName()).isEqualTo("Maximum Fan Speed Limit");
        }

        @Test
        @DisplayName("should return empty for unknown rule ID")
        void shouldReturnEmptyForUnknownRuleId() {
            final var rule = engine.findRuleById("UNKNOWN_RULE");

            assertThat(rule).isEmpty();
        }

        @Test
        @DisplayName("should evaluate only applicable rules")
        void shouldEvaluateOnlyApplicableRules() {
            // FAN device - only MaxFanSpeedRule should apply
            final var context = SafetyContext.builder()
                    .deviceId(FAN_DEVICE)
                    .deviceType(DeviceType.FAN)
                    .proposedValue(new FanValue(2))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isAccepted()).isTrue();
            assertThat(result.evaluatedRules()).containsExactly("MAX_FAN_SPEED");
        }

        @Test
        @DisplayName("should stop at first refusal")
        void shouldStopAtFirstRefusal() {
            // Fire relay with pump ON - should be refused by PumpFireInterlockRule
            final var pumpDesired = new DesiredDeviceState(PUMP_RELAY, DeviceType.RELAY, new RelayValue(true));
            final var pumpSnapshot = new DeviceTwinSnapshot(PUMP_RELAY, DeviceType.RELAY, null, null, pumpDesired);

            final var context = SafetyContext.builder()
                    .deviceId(FIRE_RELAY)
                    .deviceType(DeviceType.RELAY)
                    .proposedValue(new RelayValue(false))
                    .relatedDeviceStates(Map.of(PUMP_RELAY, pumpSnapshot))
                    .build();

            final var result = engine.evaluate(context);

            assertThat(result.isRefused()).isTrue();
            // Only PumpFireInterlockRule should be in evaluated rules (stops at refusal)
            assertThat(result.evaluatedRules()).contains("PUMP_FIRE_INTERLOCK");
        }
    }

    @Nested
    @DisplayName("Hardcoded Rules Fallback")
    class HardcodedRulesFallbackTests {

        @Test
        @DisplayName("evaluateHardcodedOnly should only evaluate hardcoded rules")
        void evaluateHardcodedOnlyShouldOnlyEvaluateHardcodedRules() {
            // Create a mock configurable rule
            final var configurableRule = new TestConfigurableRule();
            final var engine = new SafetyRuleEngine(
                    List.of(new MaxFanSpeedRule(), configurableRule),
                    meterRegistry
            );

            final var context = SafetyContext.builder()
                    .deviceId(FAN_DEVICE)
                    .deviceType(DeviceType.FAN)
                    .proposedValue(new FanValue(3))
                    .build();

            // evaluateHardcodedOnly should skip the configurable rule
            final var result = engine.evaluateHardcodedOnly(context);

            assertThat(result.isAccepted()).isTrue();
            assertThat(result.evaluatedRules()).contains("MAX_FAN_SPEED");
            assertThat(result.evaluatedRules()).doesNotContain("TEST_CONFIGURABLE");
        }
    }

    /**
     * Test configurable rule (non-hardcoded) for testing fallback behavior.
     */
    private static class TestConfigurableRule implements SafetyRule {
        @Override
        public String getId() {
            return "TEST_CONFIGURABLE";
        }

        @Override
        public String getName() {
            return "Test Configurable Rule";
        }

        @Override
        public RuleCategory getCategory() {
            return RuleCategory.SYSTEM_SAFETY;
        }

        @Override
        public int getPriority() {
            return 100;
        }

        @Override
        public String getDescription() {
            return "Test rule";
        }

        @Override
        public boolean appliesTo(SafetyContext context) {
            return true;
        }

        @Override
        public ValidationResult evaluate(SafetyContext context) {
            return ValidationResult.Accepted.of(getId());
        }

        @Override
        public Optional<Object> suggestCorrection(SafetyContext context) {
            return Optional.empty();
        }
    }
}

