package dev.dmgiangi.core.server.application.override;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.domain.service.SafetyEvaluationResult;
import dev.dmgiangi.core.server.domain.service.SafetyValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DefaultOverrideValidationPipeline.
 * Tests stacking semantics, precedence rules, and safety validation.
 */
@DisplayName("DefaultOverrideValidationPipeline")
@ExtendWith(MockitoExtension.class)
class DefaultOverrideValidationPipelineTest {

    private static final String DEVICE_ID = "controller1:relay1";
    private static final String SYSTEM_ID = "system-123";
    private static final Instant TIMESTAMP = Instant.parse("2026-01-29T10:00:00Z");

    @Mock
    private SafetyValidator safetyValidator;

    @Mock
    private OverrideRepository overrideRepository;

    @Mock
    private DeviceStateRepository deviceStateRepository;

    @Mock
    private FunctionalSystemRepository functionalSystemRepository;

    private DefaultOverrideValidationPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new DefaultOverrideValidationPipeline(
                safetyValidator,
                overrideRepository,
                deviceStateRepository,
                functionalSystemRepository,
                new SimpleMeterRegistry()
        );
    }

    @Nested
    @DisplayName("Device Override Validation")
    class DeviceOverrideValidationTests {

        @Test
        @DisplayName("should apply device override when safety accepts")
        void shouldApplyDeviceOverrideWhenSafetyAccepts() {
            final var request = createDeviceOverrideRequest(new RelayValue(true), OverrideCategory.MANUAL);
            final var deviceId = new DeviceId("controller1", "relay1");
            final var snapshot = new DeviceTwinSnapshot(deviceId, DeviceType.RELAY, null, null, null);

            when(deviceStateRepository.findTwinSnapshot(deviceId)).thenReturn(Optional.of(snapshot));
            when(functionalSystemRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(deviceId), any(), eq(snapshot), isNull()))
                    .thenReturn(SafetyEvaluationResult.accepted(List.of()));
            when(overrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            final var result = pipeline.validate(request);

            assertThat(result.isApplied()).isTrue();
            assertThat(result.isBlocked()).isFalse();
            assertThat(result.getAppliedOverride()).isPresent();
            assertThat(result.getAppliedOverride().get().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should block device override when safety refuses")
        void shouldBlockDeviceOverrideWhenSafetyRefuses() {
            final var request = createDeviceOverrideRequest(new RelayValue(false), OverrideCategory.MANUAL);
            final var deviceId = new DeviceId("controller1", "relay1");
            final var snapshot = new DeviceTwinSnapshot(deviceId, DeviceType.RELAY, null, null, null);

            when(deviceStateRepository.findTwinSnapshot(deviceId)).thenReturn(Optional.of(snapshot));
            when(functionalSystemRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(deviceId), any(), eq(snapshot), isNull()))
                    .thenReturn(SafetyEvaluationResult.refused(
                            new dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Refused(
                                    "PUMP_FIRE_INTERLOCK",
                                    "Cannot turn OFF fire while pump is ON",
                                    "Safety rule blocked"
                            ),
                            List.of("PUMP_FIRE_INTERLOCK")
                    ));

            final var result = pipeline.validate(request);

            assertThat(result.isBlocked()).isTrue();
            assertThat(result.isApplied()).isFalse();
            assertThat(result.getAppliedOverride()).isEmpty();
            verify(overrideRepository, never()).save(any());
        }

        @Test
        @DisplayName("should modify device override when safety modifies value")
        void shouldModifyDeviceOverrideWhenSafetyModifiesValue() {
            final var request = createDeviceOverrideRequest(new FanValue(4), OverrideCategory.MANUAL);
            final var deviceId = new DeviceId("controller1", "relay1");
            final var snapshot = new DeviceTwinSnapshot(deviceId, DeviceType.FAN, null, null, null);

            when(deviceStateRepository.findTwinSnapshot(deviceId)).thenReturn(Optional.of(snapshot));
            when(functionalSystemRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(deviceId), any(), eq(snapshot), isNull()))
                    .thenReturn(SafetyEvaluationResult.modified(
                            new FanValue(4),
                            new FanValue(3),
                            List.of("MAX_FAN_SPEED")
                    ));
            when(overrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            final var result = pipeline.validate(request);

            assertThat(result.isModified()).isTrue();
            assertThat(result.isApplied()).isTrue();
            assertThat(result.getAppliedOverride()).isPresent();
            assertThat(result.getAppliedOverride().get().value()).isEqualTo(new FanValue(3));

            final var modified = (OverrideValidationResult.Modified) result;
            assertThat(modified.originalValue()).isEqualTo(new FanValue(4));
            assertThat(modified.modifiedValue()).isEqualTo(new FanValue(3));
        }
    }

    private OverrideRequest createDeviceOverrideRequest(DeviceValue value, OverrideCategory category) {
        return OverrideRequest.builder()
                .targetId(DEVICE_ID)
                .scope(OverrideScope.DEVICE)
                .category(category)
                .value(value)
                .reason("Test override")
                .createdBy("test-user")
                .build();
    }

    @Nested
    @DisplayName("System Override Validation")
    class SystemOverrideValidationTests {

        @Test
        @DisplayName("should apply system override when system exists")
        void shouldApplySystemOverrideWhenSystemExists() {
            final var request = createSystemOverrideRequest(new RelayValue(true), OverrideCategory.MAINTENANCE);
            final var systemData = createSystemData();

            when(functionalSystemRepository.findById(SYSTEM_ID)).thenReturn(Optional.of(systemData));
            when(overrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            final var result = pipeline.validate(request);

            assertThat(result.isApplied()).isTrue();
            assertThat(result.getAppliedOverride()).isPresent();
            assertThat(result.getAppliedOverride().get().scope()).isEqualTo(OverrideScope.SYSTEM);
        }

        @Test
        @DisplayName("should block system override when system not found")
        void shouldBlockSystemOverrideWhenSystemNotFound() {
            final var request = createSystemOverrideRequest(new RelayValue(true), OverrideCategory.MAINTENANCE);

            when(functionalSystemRepository.findById(SYSTEM_ID)).thenReturn(Optional.empty());

            final var result = pipeline.validate(request);

            assertThat(result.isBlocked()).isTrue();
            final var blocked = (OverrideValidationResult.Blocked) result;
            assertThat(blocked.reason()).contains("System not found");
            verify(overrideRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Override Precedence Resolution")
    class OverridePrecedenceTests {

        @Test
        @DisplayName("should resolve highest category override for device")
        void shouldResolveHighestCategoryOverrideForDevice() {
            final var manualOverride = createOverrideData(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, new RelayValue(false));
            final var emergencyOverride = createOverrideData(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.EMERGENCY, new RelayValue(true));

            when(overrideRepository.findActiveByTarget(DEVICE_ID))
                    .thenReturn(List.of(emergencyOverride, manualOverride));

            final var result = pipeline.resolveEffective(DEVICE_ID);

            assertThat(result).isPresent();
            assertThat(result.get().category()).isEqualTo(OverrideCategory.EMERGENCY);
            assertThat(result.get().value()).isEqualTo(new RelayValue(true));
        }

        @Test
        @DisplayName("should prefer device override over system override at same category")
        void shouldPreferDeviceOverrideOverSystemOverrideAtSameCategory() {
            final var deviceOverride = createOverrideData(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MAINTENANCE, new RelayValue(true));
            final var systemOverride = createOverrideData(SYSTEM_ID, OverrideScope.SYSTEM, OverrideCategory.MAINTENANCE, new RelayValue(false));

            when(overrideRepository.findActiveByTarget(DEVICE_ID)).thenReturn(List.of(deviceOverride));
            when(overrideRepository.findActiveByTarget(SYSTEM_ID)).thenReturn(List.of(systemOverride));

            final var result = pipeline.resolveEffectiveForDevice(DEVICE_ID, SYSTEM_ID);

            assertThat(result).isPresent();
            assertThat(result.get().value()).isEqualTo(new RelayValue(true));
            assertThat(result.get().isFromSystem()).isFalse();
        }

        @Test
        @DisplayName("should use system override when no device override exists")
        void shouldUseSystemOverrideWhenNoDeviceOverrideExists() {
            final var systemOverride = createOverrideData(SYSTEM_ID, OverrideScope.SYSTEM, OverrideCategory.EMERGENCY, new RelayValue(true));

            when(overrideRepository.findActiveByTarget(DEVICE_ID)).thenReturn(List.of());
            when(overrideRepository.findActiveByTarget(SYSTEM_ID)).thenReturn(List.of(systemOverride));

            final var result = pipeline.resolveEffectiveForDevice(DEVICE_ID, SYSTEM_ID);

            assertThat(result).isPresent();
            assertThat(result.get().value()).isEqualTo(new RelayValue(true));
            assertThat(result.get().isFromSystem()).isTrue();
        }

        @Test
        @DisplayName("should return empty when no overrides exist")
        void shouldReturnEmptyWhenNoOverridesExist() {
            when(overrideRepository.findActiveByTarget(DEVICE_ID)).thenReturn(List.of());
            when(overrideRepository.findActiveByTarget(SYSTEM_ID)).thenReturn(List.of());

            final var result = pipeline.resolveEffectiveForDevice(DEVICE_ID, SYSTEM_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("higher category system override should win over lower category device override")
        void higherCategorySystemOverrideShouldWinOverLowerCategoryDeviceOverride() {
            final var deviceOverride = createOverrideData(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, new RelayValue(false));
            final var systemOverride = createOverrideData(SYSTEM_ID, OverrideScope.SYSTEM, OverrideCategory.EMERGENCY, new RelayValue(true));

            when(overrideRepository.findActiveByTarget(DEVICE_ID)).thenReturn(List.of(deviceOverride));
            when(overrideRepository.findActiveByTarget(SYSTEM_ID)).thenReturn(List.of(systemOverride));

            final var result = pipeline.resolveEffectiveForDevice(DEVICE_ID, SYSTEM_ID);

            assertThat(result).isPresent();
            assertThat(result.get().category()).isEqualTo(OverrideCategory.EMERGENCY);
            assertThat(result.get().value()).isEqualTo(new RelayValue(true));
            assertThat(result.get().isFromSystem()).isTrue();
        }
    }

    @Nested
    @DisplayName("Override Cancellation")
    class OverrideCancellationTests {

        @Test
        @DisplayName("should cancel existing override")
        void shouldCancelExistingOverride() {
            final var existingOverride = createOverrideData(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, new RelayValue(true));

            when(overrideRepository.findByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL))
                    .thenReturn(Optional.of(existingOverride));

            final var result = pipeline.cancelOverride(DEVICE_ID, OverrideCategory.MANUAL);

            assertThat(result).isTrue();
            verify(overrideRepository).deleteByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL);
        }

        @Test
        @DisplayName("should return false when no override to cancel")
        void shouldReturnFalseWhenNoOverrideToCancel() {
            when(overrideRepository.findByTargetAndCategory(DEVICE_ID, OverrideCategory.MANUAL))
                    .thenReturn(Optional.empty());

            final var result = pipeline.cancelOverride(DEVICE_ID, OverrideCategory.MANUAL);

            assertThat(result).isFalse();
            verify(overrideRepository, never()).deleteByTargetAndCategory(any(), any());
        }
    }

    @Nested
    @DisplayName("Stacking Semantics")
    class StackingSemanticsTests {

        @Test
        @DisplayName("should replace existing override at same target and category")
        void shouldReplaceExistingOverrideAtSameTargetAndCategory() {
            final var request = createDeviceOverrideRequest(new RelayValue(true), OverrideCategory.MANUAL);
            final var deviceId = new DeviceId("controller1", "relay1");
            final var snapshot = new DeviceTwinSnapshot(deviceId, DeviceType.RELAY, null, null, null);

            when(deviceStateRepository.findTwinSnapshot(deviceId)).thenReturn(Optional.of(snapshot));
            when(functionalSystemRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());
            when(safetyValidator.validate(eq(deviceId), any(), eq(snapshot), isNull()))
                    .thenReturn(SafetyEvaluationResult.accepted(List.of()));

            final var captor = ArgumentCaptor.forClass(OverrideData.class);
            when(overrideRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            pipeline.validate(request);

            final var savedOverride = captor.getValue();
            assertThat(savedOverride.targetId()).isEqualTo(DEVICE_ID);
            assertThat(savedOverride.category()).isEqualTo(OverrideCategory.MANUAL);
        }

        @Test
        @DisplayName("should list all active overrides for target")
        void shouldListAllActiveOverridesForTarget() {
            final var manualOverride = createOverrideData(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MANUAL, new RelayValue(false));
            final var maintenanceOverride = createOverrideData(DEVICE_ID, OverrideScope.DEVICE, OverrideCategory.MAINTENANCE, new RelayValue(true));

            when(overrideRepository.findActiveByTarget(DEVICE_ID))
                    .thenReturn(List.of(maintenanceOverride, manualOverride));

            final var result = pipeline.listActiveOverrides(DEVICE_ID);

            assertThat(result).hasSize(2);
        }
    }

    private OverrideRequest createSystemOverrideRequest(DeviceValue value, OverrideCategory category) {
        return OverrideRequest.builder()
                .targetId(SYSTEM_ID)
                .scope(OverrideScope.SYSTEM)
                .category(category)
                .value(value)
                .reason("Test system override")
                .createdBy("test-user")
                .build();
    }

    private FunctionalSystemData createSystemData() {
        return new FunctionalSystemData(
                SYSTEM_ID,
                "TERMOCAMINO",
                "Test System",
                Map.of(),
                Set.of(DEVICE_ID),
                Map.of(),
                TIMESTAMP,
                TIMESTAMP,
                "test-user",
                1L
        );
    }

    private OverrideData createOverrideData(String targetId, OverrideScope scope, OverrideCategory category, DeviceValue value) {
        return OverrideData.create(targetId, scope, category, value, "Test reason", null, "test-user");
    }
}

