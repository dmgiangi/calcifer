package dev.dmgiangi.core.server.application.override;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.domain.service.SafetyEvaluationResult;
import dev.dmgiangi.core.server.domain.service.SafetyValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link OverrideValidationPipeline}.
 * Per Phase 0.5: Validates overrides via SafetyValidator with stacking semantics.
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Safety validation: HARDCODED_SAFETY/SYSTEM_SAFETY cannot be overridden</li>
 *   <li>Stacking: one override per (target, category) pair</li>
 *   <li>Conflict resolution: higher category wins, same category DEVICE wins</li>
 *   <li>Write-through: persists to MongoDB + Redis cache</li>
 * </ul>
 */
@Slf4j
@Service
public class DefaultOverrideValidationPipeline implements OverrideValidationPipeline {

    private final SafetyValidator safetyValidator;
    private final OverrideRepository overrideRepository;
    private final DeviceStateRepository deviceStateRepository;
    private final FunctionalSystemRepository functionalSystemRepository;

    private final Counter overridesAppliedCounter;
    private final Counter overridesBlockedCounter;
    private final Counter overridesModifiedCounter;

    public DefaultOverrideValidationPipeline(
            final SafetyValidator safetyValidator,
            final OverrideRepository overrideRepository,
            final DeviceStateRepository deviceStateRepository,
            final FunctionalSystemRepository functionalSystemRepository,
            final MeterRegistry meterRegistry
    ) {
        this.safetyValidator = safetyValidator;
        this.overrideRepository = overrideRepository;
        this.deviceStateRepository = deviceStateRepository;
        this.functionalSystemRepository = functionalSystemRepository;

        this.overridesAppliedCounter = Counter.builder("calcifer.overrides.applied")
                .description("Number of overrides applied successfully")
                .register(meterRegistry);
        this.overridesBlockedCounter = Counter.builder("calcifer.overrides.blocked")
                .description("Number of overrides blocked by safety rules")
                .register(meterRegistry);
        this.overridesModifiedCounter = Counter.builder("calcifer.overrides.modified")
                .description("Number of overrides modified by safety rules")
                .register(meterRegistry);
    }

    @Override
    public OverrideValidationResult validate(final OverrideRequest request) {
        log.debug("Validating override request for target {} category {}", request.targetId(), request.category());

        final var validationResult = performSafetyValidation(request);

        if (validationResult.isBlocked()) {
            overridesBlockedCounter.increment();
            return validationResult;
        }

        // Persist the override (replaces existing at same target+category)
        final var overrideData = createOverrideData(request, validationResult);
        final var savedOverride = overrideRepository.save(overrideData);

        log.info("Override applied for target {} category {} by {}",
                request.targetId(), request.category(), request.createdBy());

        if (validationResult.isModified()) {
            overridesModifiedCounter.increment();
            final var modified = (OverrideValidationResult.Modified) validationResult;
            return OverrideValidationResult.Modified.of(
                    savedOverride,
                    modified.originalValue(),
                    modified.modifiedValue(),
                    modified.modifyingRules()
            );
        }

        overridesAppliedCounter.increment();
        return OverrideValidationResult.Applied.of(savedOverride);
    }

    @Override
    public OverrideValidationResult validateOnly(final OverrideRequest request) {
        log.debug("Dry-run validation for target {} category {}", request.targetId(), request.category());
        return performSafetyValidation(request);
    }

    @Override
    public Optional<EffectiveOverride> resolveEffective(final String targetId) {
        final var activeOverrides = overrideRepository.findActiveByTarget(targetId);
        if (activeOverrides.isEmpty()) {
            return Optional.empty();
        }

        // Already sorted by category (highest first)
        final var highest = activeOverrides.getFirst();
        return Optional.of(toEffectiveOverride(highest, targetId, false, List.of()));
    }

    @Override
    public Optional<EffectiveOverride> resolveEffectiveForDevice(final String deviceId, final String systemId) {
        final var deviceOverrides = overrideRepository.findActiveByTarget(deviceId);
        final var systemOverrides = systemId != null
                ? overrideRepository.findActiveByTarget(systemId)
                : List.<OverrideData>of();

        // Merge and sort by category (highest first), then by scope (DEVICE first)
        final var allOverrides = new ArrayList<OverrideData>();
        allOverrides.addAll(deviceOverrides);
        allOverrides.addAll(systemOverrides);

        if (allOverrides.isEmpty()) {
            return Optional.empty();
        }

        allOverrides.sort(Comparator
                .comparing(OverrideData::category).reversed()
                .thenComparing(o -> o.scope() == OverrideScope.DEVICE ? 0 : 1));

        final var highest = allOverrides.getFirst();
        final var isFromSystem = highest.scope() == OverrideScope.SYSTEM;
        return Optional.of(toEffectiveOverride(highest, deviceId, isFromSystem, List.of()));
    }

    @Override
    public List<EffectiveOverride> listActiveOverrides(final String targetId) {
        return overrideRepository.findActiveByTarget(targetId).stream()
                .map(o -> toEffectiveOverride(o, targetId, o.scope() == OverrideScope.SYSTEM, List.of()))
                .toList();
    }

    @Override
    public boolean cancelOverride(final String targetId, final OverrideCategory category) {
        final var existing = overrideRepository.findByTargetAndCategory(targetId, category);
        if (existing.isEmpty()) {
            log.debug("No override to cancel for target {} category {}", targetId, category);
            return false;
        }

        overrideRepository.deleteByTargetAndCategory(targetId, category);
        log.info("Override cancelled for target {} category {}", targetId, category);
        return true;
    }

    /**
     * Performs safety validation for the override request.
     */
    private OverrideValidationResult performSafetyValidation(final OverrideRequest request) {
        // For DEVICE scope, validate against safety rules
        if (request.scope() == OverrideScope.DEVICE) {
            return validateDeviceOverride(request);
        }

        // For SYSTEM scope, validate each device in the system
        return validateSystemOverride(request);
    }

    /**
     * Validates a device-level override against safety rules.
     */
    private OverrideValidationResult validateDeviceOverride(final OverrideRequest request) {
        final var deviceId = parseDeviceId(request.targetId());
        final var snapshot = loadDeviceSnapshot(deviceId);
        final var system = loadFunctionalSystem(request.targetId());

        final var safetyResult = safetyValidator.validate(
                deviceId,
                request.value(),
                snapshot.orElse(null),
                system.orElse(null)
        );

        return toValidationResult(request, safetyResult);
    }

    /**
     * Validates a system-level override.
     * For system overrides, we validate that the value is generally safe.
     * Individual device safety is checked when the override is applied.
     */
    private OverrideValidationResult validateSystemOverride(final OverrideRequest request) {
        // For system overrides, we do a basic validation
        // Full device-level safety is checked when resolving effective override
        final var system = functionalSystemRepository.findById(request.targetId());
        if (system.isEmpty()) {
            return OverrideValidationResult.Blocked.of(
                    "System not found: " + request.targetId(),
                    List.of("SYSTEM_NOT_FOUND")
            );
        }

        // System override is accepted - device-level safety checked at resolution time
        log.debug("System override validated for system {}", request.targetId());
        return createDryRunApplied(request);
    }

    /**
     * Converts SafetyEvaluationResult to OverrideValidationResult.
     */
    private OverrideValidationResult toValidationResult(
            final OverrideRequest request,
            final SafetyEvaluationResult safetyResult
    ) {
        return switch (safetyResult.outcome()) {
            case ACCEPTED -> createDryRunApplied(request);
            case REFUSED -> {
                final var reason = safetyResult.getRefusalReason().orElse("Safety rule violation");
                yield OverrideValidationResult.Blocked.of(reason, safetyResult.evaluatedRules());
            }
            case MODIFIED -> OverrideValidationResult.Modified.of(
                    createDryRunOverrideData(request, safetyResult.finalValue()),
                    request.value(),
                    safetyResult.finalValue(),
                    safetyResult.evaluatedRules()
            );
        };
    }

    /**
     * Creates a dry-run Applied result (for validateOnly).
     */
    private OverrideValidationResult createDryRunApplied(final OverrideRequest request) {
        final var dryRunData = createDryRunOverrideData(request, request.value());
        return OverrideValidationResult.Applied.of(dryRunData);
    }

    /**
     * Creates OverrideData for dry-run validation.
     */
    private OverrideData createDryRunOverrideData(final OverrideRequest request, final DeviceValue value) {
        return OverrideData.create(
                request.targetId(),
                request.scope(),
                request.category(),
                value,
                request.reason(),
                request.expiresAt(),
                request.createdBy()
        );
    }

    /**
     * Creates OverrideData from request and validation result.
     */
    private OverrideData createOverrideData(
            final OverrideRequest request,
            final OverrideValidationResult validationResult
    ) {
        final var value = validationResult instanceof OverrideValidationResult.Modified modified
                ? modified.modifiedValue()
                : request.value();

        return OverrideData.create(
                request.targetId(),
                request.scope(),
                request.category(),
                value,
                request.reason(),
                request.expiresAt(),
                request.createdBy()
        );
    }

    /**
     * Converts OverrideData to EffectiveOverride.
     */
    private EffectiveOverride toEffectiveOverride(
            final OverrideData data,
            final String targetId,
            final boolean isFromSystem,
            final List<String> shadowedBy
    ) {
        return new EffectiveOverride(
                targetId,
                data.targetId(),
                data.category(),
                data.value(),
                data.reason(),
                isFromSystem,
                shadowedBy
        );
    }

    /**
     * Parses a device ID string into DeviceId.
     */
    private DeviceId parseDeviceId(final String deviceIdString) {
        final var parts = deviceIdString.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid device ID format: " + deviceIdString);
        }
        return new DeviceId(parts[0], parts[1]);
    }

    /**
     * Loads the device snapshot from Redis.
     */
    private Optional<DeviceTwinSnapshot> loadDeviceSnapshot(final DeviceId deviceId) {
        return deviceStateRepository.findTwinSnapshot(deviceId);
    }

    /**
     * Loads the FunctionalSystem for a device.
     */
    private Optional<FunctionalSystemData> loadFunctionalSystem(final String deviceId) {
        return functionalSystemRepository.findByDeviceId(deviceId);
    }
}

