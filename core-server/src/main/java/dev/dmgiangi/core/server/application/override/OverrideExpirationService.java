package dev.dmgiangi.core.server.application.override;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.domain.service.ReconciliationCoordinator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Service for handling override expiration.
 * Per Phase 0.5: On expiry, next category takes over.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Scheduled cleanup of expired overrides</li>
 *   <li>Publish OverrideExpiredEvent for each expired override</li>
 *   <li>Trigger reconciliation for affected devices</li>
 * </ul>
 */
@Slf4j
@Service
public class OverrideExpirationService {

    private final OverrideRepository overrideRepository;
    private final FunctionalSystemRepository functionalSystemRepository;
    private final ReconciliationCoordinator reconciliationCoordinator;
    private final ApplicationEventPublisher eventPublisher;

    private final Counter expiredOverridesCounter;
    private final Counter expirationCyclesCounter;

    public OverrideExpirationService(
            final OverrideRepository overrideRepository,
            final FunctionalSystemRepository functionalSystemRepository,
            final ReconciliationCoordinator reconciliationCoordinator,
            final ApplicationEventPublisher eventPublisher,
            final MeterRegistry meterRegistry
    ) {
        this.overrideRepository = overrideRepository;
        this.functionalSystemRepository = functionalSystemRepository;
        this.reconciliationCoordinator = reconciliationCoordinator;
        this.eventPublisher = eventPublisher;

        this.expiredOverridesCounter = Counter.builder("calcifer.overrides.expired")
                .description("Number of overrides that have expired")
                .register(meterRegistry);
        this.expirationCyclesCounter = Counter.builder("calcifer.overrides.expiration_cycles")
                .description("Number of expiration cleanup cycles executed")
                .register(meterRegistry);
    }

    /**
     * Scheduled job to clean up expired overrides.
     * Runs every minute by default.
     */
    @Scheduled(cron = "${app.override.expiration-cron:0 * * * * *}")
    public void cleanupExpiredOverrides() {
        log.debug("Starting override expiration cleanup");
        expirationCyclesCounter.increment();

        final var expiredOverrides = overrideRepository.findExpired();
        if (expiredOverrides.isEmpty()) {
            log.trace("No expired overrides found");
            return;
        }

        log.info("Found {} expired overrides to clean up", expiredOverrides.size());

        // Track targets that need reconciliation
        final Set<String> devicesToReconcile = new HashSet<>();

        for (final var override : expiredOverrides) {
            processExpiredOverride(override, devicesToReconcile);
        }

        // Trigger reconciliation for affected devices
        reconcileAffectedDevices(devicesToReconcile);

        log.info("Override expiration cleanup completed. Processed {} overrides, reconciling {} devices",
                expiredOverrides.size(), devicesToReconcile.size());
    }

    /**
     * Processes a single expired override.
     */
    private void processExpiredOverride(final OverrideData override, final Set<String> devicesToReconcile) {
        try {
            log.debug("Processing expired override for target {} category {}",
                    override.targetId(), override.category());

            // Delete the expired override
            overrideRepository.deleteByTargetAndCategory(override.targetId(), override.category());
            expiredOverridesCounter.increment();

            // Publish expiration event
            publishOverrideExpiredEvent(override);

            // Track devices for reconciliation
            if (override.scope() == OverrideScope.DEVICE) {
                devicesToReconcile.add(override.targetId());
            } else {
                // System override - add all devices in the system
                collectSystemDevices(override.targetId(), devicesToReconcile);
            }

            log.info("Override expired for target {} category {} (was set by {})",
                    override.targetId(), override.category(), override.createdBy());

        } catch (Exception e) {
            log.error("Failed to process expired override for target {}: {}",
                    override.targetId(), e.getMessage(), e);
        }
    }

    /**
     * Collects all device IDs from a system.
     */
    private void collectSystemDevices(final String systemId, final Set<String> devices) {
        functionalSystemRepository.findById(systemId)
                .ifPresent(system -> devices.addAll(system.deviceIds()));
    }

    /**
     * Triggers reconciliation for all affected devices.
     */
    private void reconcileAffectedDevices(final Set<String> deviceIds) {
        for (final var deviceIdStr : deviceIds) {
            try {
                final var deviceId = parseDeviceId(deviceIdStr);
                reconciliationCoordinator.reconcile(deviceId);
                log.debug("Triggered reconciliation for device {} after override expiration", deviceIdStr);
            } catch (Exception e) {
                log.error("Failed to reconcile device {} after override expiration: {}",
                        deviceIdStr, e.getMessage());
            }
        }
    }

    /**
     * Publishes an OverrideExpiredEvent.
     */
    private void publishOverrideExpiredEvent(final OverrideData override) {
        final var event = new OverrideExpiredEvent(
                override.targetId(),
                override.scope(),
                override.category(),
                override.reason(),
                override.createdBy(),
                override.createdAt(),
                Instant.now()
        );
        eventPublisher.publishEvent(event);
    }

    private DeviceId parseDeviceId(final String deviceIdString) {
        final var parts = deviceIdString.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid device ID format: " + deviceIdString);
        }
        return new DeviceId(parts[0], parts[1]);
    }

    /**
     * Event published when an override expires.
     */
    public record OverrideExpiredEvent(
            String targetId,
            OverrideScope scope,
            OverrideCategory category,
            String reason,
            String createdBy,
            Instant createdAt,
            Instant expiredAt
    ) {
    }
}

