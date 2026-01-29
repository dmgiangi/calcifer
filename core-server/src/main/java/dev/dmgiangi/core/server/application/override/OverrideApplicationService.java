package dev.dmgiangi.core.server.application.override;

import dev.dmgiangi.core.server.application.override.OverrideValidationPipeline.EffectiveOverride;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.domain.service.DeviceSystemMappingService;
import dev.dmgiangi.core.server.domain.service.ReconciliationCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application service for override management.
 * Orchestrates override operations and triggers reconciliation.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Apply overrides via validation pipeline</li>
 *   <li>Cancel overrides and trigger reconciliation</li>
 *   <li>List active overrides for targets</li>
 *   <li>Resolve effective override for devices</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverrideApplicationService {

    private final OverrideValidationPipeline validationPipeline;
    private final OverrideRepository overrideRepository;
    private final DeviceSystemMappingService deviceSystemMappingService;
    private final FunctionalSystemRepository functionalSystemRepository;
    private final ReconciliationCoordinator reconciliationCoordinator;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Applies a device override.
     *
     * @param deviceId  the device ID (format: "controllerId:componentId")
     * @param category  the override category
     * @param value     the override value
     * @param reason    the reason for the override
     * @param ttl       optional time-to-live (null = permanent)
     * @param createdBy the user/system creating the override
     * @return the validation result
     */
    public OverrideValidationResult applyDeviceOverride(
            final String deviceId,
            final OverrideCategory category,
            final DeviceValue value,
            final String reason,
            final Duration ttl,
            final String createdBy
    ) {
        log.info("Applying device override for {} category {} by {}", deviceId, category, createdBy);

        final var request = OverrideRequest.builder()
                .targetId(deviceId)
                .scope(OverrideScope.DEVICE)
                .category(category)
                .value(value)
                .reason(reason)
                .ttl(ttl)
                .createdBy(createdBy)
                .build();

        final var result = validationPipeline.validate(request);

        if (result.isApplied()) {
            // Trigger reconciliation for the device
            triggerDeviceReconciliation(deviceId);
            // Publish event for audit/notification
            publishOverrideAppliedEvent(deviceId, category, result);
        }

        return result;
    }

    /**
     * Applies a system override (affects all devices in the system).
     *
     * @param systemId  the system ID
     * @param category  the override category
     * @param value     the override value
     * @param reason    the reason for the override
     * @param ttl       optional time-to-live (null = permanent)
     * @param createdBy the user/system creating the override
     * @return the validation result
     */
    public OverrideValidationResult applySystemOverride(
            final String systemId,
            final OverrideCategory category,
            final DeviceValue value,
            final String reason,
            final Duration ttl,
            final String createdBy
    ) {
        log.info("Applying system override for {} category {} by {}", systemId, category, createdBy);

        final var request = OverrideRequest.builder()
                .targetId(systemId)
                .scope(OverrideScope.SYSTEM)
                .category(category)
                .value(value)
                .reason(reason)
                .ttl(ttl)
                .createdBy(createdBy)
                .build();

        final var result = validationPipeline.validate(request);

        if (result.isApplied()) {
            // Trigger reconciliation for all devices in the system
            triggerSystemReconciliation(systemId);
            // Publish event for audit/notification
            publishOverrideAppliedEvent(systemId, category, result);
        }

        return result;
    }

    /**
     * Cancels a device override.
     *
     * @param deviceId the device ID
     * @param category the category to cancel
     * @return true if an override was cancelled
     */
    public boolean cancelDeviceOverride(final String deviceId, final OverrideCategory category) {
        log.info("Cancelling device override for {} category {}", deviceId, category);

        final var cancelled = validationPipeline.cancelOverride(deviceId, category);

        if (cancelled) {
            // Trigger reconciliation - next lower category takes over
            triggerDeviceReconciliation(deviceId);
            publishOverrideCancelledEvent(deviceId, category);
        }

        return cancelled;
    }

    /**
     * Cancels a system override.
     *
     * @param systemId the system ID
     * @param category the category to cancel
     * @return true if an override was cancelled
     */
    public boolean cancelSystemOverride(final String systemId, final OverrideCategory category) {
        log.info("Cancelling system override for {} category {}", systemId, category);

        final var cancelled = validationPipeline.cancelOverride(systemId, category);

        if (cancelled) {
            // Trigger reconciliation for all devices in the system
            triggerSystemReconciliation(systemId);
            publishOverrideCancelledEvent(systemId, category);
        }

        return cancelled;
    }

    /**
     * Lists all active overrides for a device.
     *
     * @param deviceId the device ID
     * @return list of active overrides, highest priority first
     */
    public List<OverrideData> listDeviceOverrides(final String deviceId) {
        return overrideRepository.findActiveByTarget(deviceId);
    }

    /**
     * Lists all active overrides for a system.
     *
     * @param systemId the system ID
     * @return list of active overrides, highest priority first
     */
    public List<OverrideData> listSystemOverrides(final String systemId) {
        return overrideRepository.findActiveByTarget(systemId);
    }

    /**
     * Resolves the effective override for a device.
     * Considers both device-level and system-level overrides.
     *
     * @param deviceId the device ID
     * @return Optional containing the effective override
     */
    public Optional<EffectiveOverride> resolveEffectiveOverride(final String deviceId) {
        // Find the system this device belongs to
        final var systemId = deviceSystemMappingService.findSystemIdByDevice(parseDeviceId(deviceId))
                .map(id -> id.value().toString());
        return validationPipeline.resolveEffectiveForDevice(deviceId, systemId.orElse(null));
    }

    /**
     * Gets the effective value for a device, considering overrides.
     *
     * @param deviceId the device ID
     * @return Optional containing the effective value from override, or empty if no override
     */
    public Optional<DeviceValue> getEffectiveOverrideValue(final String deviceId) {
        return resolveEffectiveOverride(deviceId)
                .map(EffectiveOverride::value)
                .filter(DeviceValue.class::isInstance)
                .map(DeviceValue.class::cast);
    }

    /**
     * Checks if a device has any active override.
     *
     * @param deviceId the device ID
     * @return true if device has an active override
     */
    public boolean hasActiveOverride(final String deviceId) {
        return resolveEffectiveOverride(deviceId).isPresent();
    }

    // ========== Private Helper Methods ==========

    private void triggerDeviceReconciliation(final String deviceId) {
        try {
            final var parsedId = parseDeviceId(deviceId);
            reconciliationCoordinator.reconcile(parsedId);
            log.debug("Triggered reconciliation for device {}", deviceId);
        } catch (Exception e) {
            log.error("Failed to trigger reconciliation for device {}: {}", deviceId, e.getMessage());
        }
    }

    private void triggerSystemReconciliation(final String systemId) {
        try {
            final var system = functionalSystemRepository.findById(systemId);
            if (system.isEmpty()) {
                log.warn("System not found for reconciliation: {}", systemId);
                return;
            }

            for (final var deviceIdStr : system.get().deviceIds()) {
                triggerDeviceReconciliation(deviceIdStr);
            }
            log.debug("Triggered reconciliation for {} devices in system {}",
                    system.get().deviceIds().size(), systemId);
        } catch (Exception e) {
            log.error("Failed to trigger system reconciliation for {}: {}", systemId, e.getMessage());
        }
    }

    private void publishOverrideAppliedEvent(
            final String targetId,
            final OverrideCategory category,
            final OverrideValidationResult result
    ) {
        final var event = new OverrideAppliedEvent(
                targetId,
                category,
                result.isModified(),
                result.getWarnings(),
                Instant.now()
        );
        eventPublisher.publishEvent(event);
    }

    private void publishOverrideCancelledEvent(final String targetId, final OverrideCategory category) {
        final var event = new OverrideCancelledEvent(targetId, category, Instant.now());
        eventPublisher.publishEvent(event);
    }

    private DeviceId parseDeviceId(final String deviceIdString) {
        final var parts = deviceIdString.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid device ID format: " + deviceIdString);
        }
        return new DeviceId(parts[0], parts[1]);
    }

    // ========== Event Records ==========

    /**
     * Event published when an override is applied.
     */
    public record OverrideAppliedEvent(
            String targetId,
            OverrideCategory category,
            boolean wasModified,
            List<String> warnings,
            Instant timestamp
    ) {
    }

    /**
     * Event published when an override is cancelled.
     */
    public record OverrideCancelledEvent(
            String targetId,
            OverrideCategory category,
            Instant timestamp
    ) {
    }
}

