package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideResolver;
import dev.dmgiangi.core.server.domain.port.OverrideResolver.ResolvedOverride;
import dev.dmgiangi.core.server.domain.service.StateCalculator.CalculationResult.ValueSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DefaultStateCalculator.
 * Tests pure function behavior, override precedence, and safety rule handling.
 */
@DisplayName("DefaultStateCalculator")
@ExtendWith(MockitoExtension.class)
class DefaultStateCalculatorTest {

    private static final DeviceId DEVICE_ID = new DeviceId("controller1", "relay1");
    private static final Instant TIMESTAMP = Instant.parse("2026-01-29T10:00:00Z");

    @Mock
    private SafetyValidator safetyValidator;

    @Mock
    private OverrideResolver overrideResolver;

    private DefaultStateCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new DefaultStateCalculator(safetyValidator, overrideResolver);
    }

    @Nested
    @DisplayName("Intent-based calculation")
    class IntentBasedCalculationTests {

        @Test
        @DisplayName("should return desired state from intent when no override")
        void shouldReturnDesiredStateFromIntent() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.accepted(java.util.List.of()));

            final var result = calculator.calculateWithDetails(snapshot, null, Map.of());

            assertThat(result.hasValue()).isTrue();
            assertThat(result.source()).isEqualTo(ValueSource.INTENT);
            assertThat(result.desiredState().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should return empty when no intent and no override")
        void shouldReturnEmptyWhenNoIntentAndNoOverride() {
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, null);

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.empty());

            final var result = calculator.calculateWithDetails(snapshot, null, Map.of());

            assertThat(result.hasValue()).isFalse();
            assertThat(result.source()).isEqualTo(ValueSource.NO_VALUE);
            assertThat(result.reason()).contains("No override or user intent");
        }

        @Test
        @DisplayName("should handle FAN device type")
        void shouldHandleFanDeviceType() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.FAN, new FanValue(3), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.FAN, intent, null, null);

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.accepted(java.util.List.of()));

            final var result = calculator.calculateWithDetails(snapshot, null, Map.of());

            assertThat(result.hasValue()).isTrue();
            assertThat(result.desiredState().value()).isEqualTo(new FanValue(3));
        }
    }

    @Nested
    @DisplayName("Override precedence")
    class OverridePrecedenceTests {

        @Test
        @DisplayName("should use override value when present")
        void shouldUseOverrideValueWhenPresent() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(false), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);
            final var override = new ResolvedOverride(
                    new RelayValue(true),
                    OverrideCategory.EMERGENCY,
                    "Emergency shutdown",
                    false
            );

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.of(override));
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.accepted(java.util.List.of()));

            final var result = calculator.calculateWithDetails(snapshot, null, Map.of());

            assertThat(result.hasValue()).isTrue();
            assertThat(result.source()).isEqualTo(ValueSource.OVERRIDE);
            assertThat(result.desiredState().value()).isEqualTo(new RelayValue(true));
            assertThat(result.reason()).contains("EMERGENCY");
        }

        @Test
        @DisplayName("should use override even when no intent exists")
        void shouldUseOverrideEvenWhenNoIntentExists() {
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, null);
            final var override = new ResolvedOverride(
                    new RelayValue(true),
                    OverrideCategory.MAINTENANCE,
                    "Maintenance mode",
                    true
            );

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.of(override));
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.accepted(java.util.List.of()));

            final var result = calculator.calculateWithDetails(snapshot, null, Map.of());

            assertThat(result.hasValue()).isTrue();
            assertThat(result.source()).isEqualTo(ValueSource.OVERRIDE);
            assertThat(result.reason()).contains("system");
        }
    }

    @Nested
    @DisplayName("Safety rule handling")
    class SafetyRuleHandlingTests {

        @Test
        @DisplayName("should return modified value when safety modifies intent")
        void shouldReturnModifiedValueWhenSafetyModifiesIntent() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.FAN, new FanValue(4), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.FAN, intent, null, null);

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.modified(
                            new FanValue(4),
                            new FanValue(3),
                            java.util.List.of("MAX_FAN_SPEED")
                    ));

            final var result = calculator.calculateWithDetails(snapshot, null, Map.of());

            assertThat(result.hasValue()).isTrue();
            assertThat(result.source()).isEqualTo(ValueSource.SAFETY_MODIFIED);
            assertThat(result.desiredState().value()).isEqualTo(new FanValue(3));
            assertThat(result.originalValue()).isEqualTo(new FanValue(4));
        }

        @Test
        @DisplayName("should return no value when safety refuses intent")
        void shouldReturnNoValueWhenSafetyRefusesIntent() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(false), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.refused(
                            new dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Refused(
                                    "PUMP_FIRE_INTERLOCK",
                                    "Cannot turn OFF fire while pump is ON",
                                    "Pump is currently ON"
                            ),
                            java.util.List.of("PUMP_FIRE_INTERLOCK")
                    ));

            final var result = calculator.calculateWithDetails(snapshot, null, Map.of());

            assertThat(result.hasValue()).isFalse();
            assertThat(result.source()).isEqualTo(ValueSource.SAFETY_REFUSED);
            assertThat(result.reason()).contains("pump");
        }

        @Test
        @DisplayName("should apply safety rules to override values too")
        void shouldApplySafetyRulesToOverrideValues() {
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, null);
            final var override = new ResolvedOverride(
                    new RelayValue(false),
                    OverrideCategory.MANUAL,
                    "Manual override",
                    false
            );

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.of(override));
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.refused(
                            new dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Refused(
                                    "PUMP_FIRE_INTERLOCK",
                                    "Cannot turn OFF fire while pump is ON",
                                    "Safety rule blocked override"
                            ),
                            java.util.List.of("PUMP_FIRE_INTERLOCK")
                    ));

            final var result = calculator.calculateWithDetails(snapshot, null, Map.of());

            assertThat(result.hasValue()).isFalse();
            assertThat(result.source()).isEqualTo(ValueSource.SAFETY_REFUSED);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should throw exception when snapshot is null")
        void shouldThrowExceptionWhenSnapshotIsNull() {
            assertThatThrownBy(() -> calculator.calculateWithDetails(null, null, Map.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Snapshot must not be null");
        }

        @Test
        @DisplayName("should handle null metadata gracefully")
        void shouldHandleNullMetadataGracefully() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.accepted(java.util.List.of()));

            final var result = calculator.calculateWithDetails(snapshot, null, null);

            assertThat(result.hasValue()).isTrue();
        }

        @Test
        @DisplayName("should use system ID for override resolution when system provided")
        void shouldUseSystemIdForOverrideResolution() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);
            final var systemId = "system-123";
            final var system = new FunctionalSystemData(
                    systemId,
                    "TERMOCAMINO",
                    "Test System",
                    Map.of(),  // configuration
                    java.util.Set.of(DEVICE_ID.toString()),  // deviceIds as Set<String>
                    Map.of(),  // failSafeDefaults
                    TIMESTAMP,  // createdAt
                    TIMESTAMP,  // updatedAt
                    "test-user",  // createdBy
                    1L  // version
            );

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), eq(systemId)))
                    .thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), eq(system), any()))
                    .thenReturn(SafetyEvaluationResult.accepted(java.util.List.of()));

            final var result = calculator.calculateWithDetails(snapshot, system, Map.of());

            assertThat(result.hasValue()).isTrue();
        }
    }

    @Nested
    @DisplayName("Simple calculate() method")
    class SimpleCalculateMethodTests {

        @Test
        @DisplayName("should return Optional with desired state when calculation succeeds")
        void shouldReturnOptionalWithDesiredState() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(DEVICE_ID), any(), eq(snapshot), isNull(), any()))
                    .thenReturn(SafetyEvaluationResult.accepted(java.util.List.of()));

            final var result = calculator.calculate(snapshot, null);

            assertThat(result).isPresent();
            assertThat(result.get().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should return empty Optional when no value can be calculated")
        void shouldReturnEmptyOptionalWhenNoValue() {
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, null);

            when(overrideResolver.resolveEffective(eq(DEVICE_ID), isNull()))
                    .thenReturn(Optional.empty());

            final var result = calculator.calculate(snapshot, null);

            assertThat(result).isEmpty();
        }
    }
}

