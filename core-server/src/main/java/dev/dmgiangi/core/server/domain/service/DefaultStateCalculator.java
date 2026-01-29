package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import dev.dmgiangi.core.server.domain.port.OverrideResolver;
import dev.dmgiangi.core.server.domain.service.StateCalculator.CalculationResult.ValueSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of {@link StateCalculator}.
 * Pure function that calculates the desired device state from inputs.
 *
 * <p>Calculation flow (per Phase 0.4/0.5):
 * <ol>
 *   <li>Check for active override (EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL)</li>
 *   <li>If no override, check for user intent</li>
 *   <li>Validate proposed value through SafetyValidator (safety rules can still modify/refuse)</li>
 *   <li>Return appropriate DesiredDeviceState based on validation result</li>
 * </ol>
 *
 * <p>This is a pure function with no side effects - persistence and events
 * are handled by the caller (ReconciliationCoordinator).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultStateCalculator implements StateCalculator {

    private final SafetyValidator safetyValidator;
    private final OverrideResolver overrideResolver;

    @Override
    public Optional<DesiredDeviceState> calculate(
            final DeviceTwinSnapshot snapshot,
            final FunctionalSystemData system
    ) {
        return calculate(snapshot, system, Map.of());
    }

    @Override
    public Optional<DesiredDeviceState> calculate(
            final DeviceTwinSnapshot snapshot,
            final FunctionalSystemData system,
            final Map<String, Object> metadata
    ) {
        final var result = calculateWithDetails(snapshot, system, metadata);
        return Optional.ofNullable(result.desiredState());
    }

    @Override
    public CalculationResult calculateWithDetails(
            final DeviceTwinSnapshot snapshot,
            final FunctionalSystemData system,
            final Map<String, Object> metadata
    ) {
        Objects.requireNonNull(snapshot, "Snapshot must not be null");

        final var deviceId = snapshot.id();
        final var systemId = system != null ? system.id() : null;

        // 1. Check for active override first (EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL)
        final var effectiveOverride = overrideResolver.resolveEffective(deviceId, systemId);

        // 2. Determine proposed value and source
        final DeviceValue proposedValue;
        final ValueSource initialSource;
        final String overrideReason;

        if (effectiveOverride.isPresent()) {
            final var override = effectiveOverride.get();
            proposedValue = override.value();
            initialSource = ValueSource.OVERRIDE;
            overrideReason = String.format("Override [%s] applied: %s (from %s)",
                    override.category(),
                    override.reason(),
                    override.isFromSystem() ? "system" : "device");
            log.debug("Using override for device {}: category={}, value={}, isFromSystem={}",
                    deviceId, override.category(), proposedValue, override.isFromSystem());
        } else {
            final var intent = snapshot.intent();
            if (intent == null) {
                log.trace("No override or intent for device {}, cannot calculate desired state", deviceId);
                return CalculationResult.noValue("No override or user intent available");
            }
            proposedValue = intent.value();
            initialSource = ValueSource.INTENT;
            overrideReason = null;
            log.debug("Using intent for device {}: value={}", deviceId, proposedValue);
        }

        // 3. Validate through safety rules (safety can still modify/refuse override values)
        final var safetyResult = safetyValidator.validate(
                deviceId,
                proposedValue,
                snapshot,
                system,
                metadata != null ? metadata : Map.of()
        );

        return processValidationResult(snapshot, proposedValue, safetyResult, initialSource, overrideReason);
    }

    /**
     * Processes the safety validation result and creates the appropriate CalculationResult.
     *
     * @param snapshot       the device twin snapshot
     * @param proposedValue  the proposed value (from override or intent)
     * @param safetyResult   the safety validation result
     * @param initialSource  the initial source of the value (OVERRIDE or INTENT)
     * @param overrideReason the override reason (null if source is INTENT)
     */
    private CalculationResult processValidationResult(
            final DeviceTwinSnapshot snapshot,
            final DeviceValue proposedValue,
            final SafetyEvaluationResult safetyResult,
            final ValueSource initialSource,
            final String overrideReason
    ) {
        return switch (safetyResult.outcome()) {
            case ACCEPTED -> {
                // Safety accepted - use proposed value as-is
                final var desiredState = createDesiredState(snapshot, proposedValue);
                if (initialSource == ValueSource.OVERRIDE) {
                    log.debug("Safety accepted override for device {}", snapshot.id());
                    yield CalculationResult.fromOverride(desiredState, overrideReason);
                } else {
                    log.debug("Safety accepted intent for device {}", snapshot.id());
                    yield CalculationResult.fromIntent(desiredState);
                }
            }

            case MODIFIED -> {
                // Safety modified the value - use the modified value
                final var modifiedValue = safetyResult.finalValue();
                final var desiredState = createDesiredState(snapshot, modifiedValue);
                final var reason = String.format("Safety rules modified %s value: %s -> %s (rules: %s)",
                        initialSource == ValueSource.OVERRIDE ? "override" : "intent",
                        proposedValue, modifiedValue, safetyResult.evaluatedRules());
                log.info("Safety modified value for device {}: {} -> {}",
                        snapshot.id(), proposedValue, modifiedValue);
                yield CalculationResult.safetyModified(desiredState, proposedValue, reason);
            }

            case REFUSED -> {
                // Safety refused - no desired state
                final var reason = safetyResult.getRefusalReason()
                        .orElse("Safety rule refused the change");
                final var ruleId = safetyResult.getBlockingRuleId().orElse("unknown");
                log.warn("Safety refused {} for device {} by rule {}: {}",
                        initialSource == ValueSource.OVERRIDE ? "override" : "intent",
                        snapshot.id(), ruleId, reason);
                yield CalculationResult.safetyRefused(reason);
            }
        };
    }

    /**
     * Creates a DesiredDeviceState from the snapshot and value.
     */
    private DesiredDeviceState createDesiredState(
            final DeviceTwinSnapshot snapshot,
            final DeviceValue value
    ) {
        return new DesiredDeviceState(
                snapshot.id(),
                snapshot.type(),
                value
        );
    }
}

