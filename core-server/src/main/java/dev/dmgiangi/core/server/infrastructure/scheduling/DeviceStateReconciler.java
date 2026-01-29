package dev.dmgiangi.core.server.infrastructure.scheduling;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.infrastructure.health.InfrastructureHealthGate;
import dev.dmgiangi.core.server.infrastructure.health.ReconcilerHealthIndicator;
import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Reconciler that periodically sends commands to output devices based on their desired state.
 *
 * <p>Per Phase 0.13 decision:
 * <ul>
 *   <li>Only reconciles devices where reported != desired (convergence check)</li>
 *   <li>Tracks metrics: reconciled, skipped, failed, cycle duration</li>
 *   <li>TRACE logging for skipped (converged) devices</li>
 *   <li>WARN logging for devices with no snapshot (data inconsistency)</li>
 * </ul>
 *
 * <p>Implements fail-stop pattern: if infrastructure is unhealthy, command generation is skipped.
 * Devices will fail-safe to their hardware defaults autonomously.
 */
@Slf4j
@Component
public class DeviceStateReconciler {

    private static final String METRIC_PREFIX = "calcifer.reconciler.";
    private static final String METRIC_DEVICES_RECONCILED = METRIC_PREFIX + "devices.reconciled";
    private static final String METRIC_DEVICES_SKIPPED = METRIC_PREFIX + "devices.skipped";
    private static final String METRIC_DEVICES_FAILED = METRIC_PREFIX + "devices.failed";
    private static final String METRIC_DEVICES_NO_SNAPSHOT = METRIC_PREFIX + "devices.no_snapshot";
    private static final String METRIC_CYCLE_DURATION = METRIC_PREFIX + "cycle.duration";

    private final DeviceStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final ReconcilerHealthIndicator healthIndicator;
    private final InfrastructureHealthGate infrastructureHealthGate;

    // Metrics
    private final Counter reconciledCounter;
    private final Counter skippedCounter;
    private final Counter failedCounter;
    private final Counter noSnapshotCounter;
    private final Timer cycleDurationTimer;

    public DeviceStateReconciler(DeviceStateRepository repository,
                                 ApplicationEventPublisher eventPublisher,
                                 ReconcilerHealthIndicator healthIndicator,
                                 InfrastructureHealthGate infrastructureHealthGate,
                                 MeterRegistry meterRegistry) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.healthIndicator = healthIndicator;
        this.infrastructureHealthGate = infrastructureHealthGate;

        // Initialize metrics
        this.reconciledCounter = Counter.builder(METRIC_DEVICES_RECONCILED)
                .description("Number of devices that received reconciliation commands")
                .register(meterRegistry);
        this.skippedCounter = Counter.builder(METRIC_DEVICES_SKIPPED)
                .description("Number of devices skipped because already converged")
                .register(meterRegistry);
        this.failedCounter = Counter.builder(METRIC_DEVICES_FAILED)
                .description("Number of devices that failed reconciliation")
                .register(meterRegistry);
        this.noSnapshotCounter = Counter.builder(METRIC_DEVICES_NO_SNAPSHOT)
                .description("Number of devices in index but with no snapshot (data inconsistency)")
                .register(meterRegistry);
        this.cycleDurationTimer = Timer.builder(METRIC_CYCLE_DURATION)
                .description("Duration of reconciliation cycle")
                .register(meterRegistry);
    }

    @Scheduled(fixedRateString = "${app.iot.polling-interval-ms:5000}")
    public void reconcileStates() {
        // Fail-stop: skip command generation if infrastructure is unhealthy
        if (!infrastructureHealthGate.isHealthy()) {
            log.warn("Reconciliation SKIPPED: infrastructure unhealthy. Devices will fail-safe autonomously.");
            healthIndicator.recordFailure("Infrastructure unhealthy - command generation halted");
            return;
        }

        cycleDurationTimer.record(() -> executeReconciliationCycle());
    }

    private void executeReconciliationCycle() {
        try {
            final var actuators = repository.findAllActiveOutputDevices();

            for (final var device : actuators) {
                reconcileDevice(device);
            }

            healthIndicator.recordSuccess();
        } catch (Exception e) {
            log.error("Reconciliation cycle failed", e);
            healthIndicator.recordFailure(e.getMessage());
        }
    }

    private void reconcileDevice(DesiredDeviceState device) {
        final var deviceId = device.id();

        try {
            // Fetch snapshot to check convergence
            final var snapshotOpt = repository.findTwinSnapshot(deviceId);

            if (snapshotOpt.isEmpty()) {
                // Device in index but no snapshot - data inconsistency
                log.warn("Reconciliation SKIPPED for device {}: no snapshot found (data inconsistency)", deviceId);
                noSnapshotCounter.increment();
                return;
            }

            final var snapshot = snapshotOpt.get();

            // Check convergence - skip if already converged
            if (snapshot.isConverged()) {
                log.trace("Reconciliation SKIPPED for device {}: already converged (reported={}, desired={})",
                        deviceId, snapshot.reported().value(), snapshot.desired().value());
                skippedCounter.increment();
                return;
            }

            // Not converged - send command
            log.debug("Reconciling device {}: reported={}, desired={}",
                    deviceId,
                    snapshot.reported() != null ? snapshot.reported().value() : "null",
                    snapshot.desired() != null ? snapshot.desired().value() : "null");

            final var deviceCommandEvent = new DeviceCommandEvent(device.id(), device.type(), device.value());
            eventPublisher.publishEvent(deviceCommandEvent);
            reconciledCounter.increment();

        } catch (Exception e) {
            log.error("Reconciliation failed for device {}", deviceId, e);
            failedCounter.increment();
        }
    }
}