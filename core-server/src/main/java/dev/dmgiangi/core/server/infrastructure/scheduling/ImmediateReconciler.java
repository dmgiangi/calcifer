package dev.dmgiangi.core.server.infrastructure.scheduling;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.event.DesiredStateCalculatedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.infrastructure.health.InfrastructureHealthGate;
import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.*;

/**
 * Immediate reconciler that bypasses the 5-second polling interval.
 * Per Phase 0.6: Implements debounce logic (50ms window per device) to accumulate rapid events.
 *
 * <p>Listens to {@link DesiredStateCalculatedEvent} and sends {@link DeviceCommandEvent}
 * immediately after the debounce window expires.
 *
 * <p>The scheduled {@link DeviceStateReconciler} remains active for drift detection and health checks.
 */
@Slf4j
@Component
public class ImmediateReconciler {

    private static final String METRIC_PREFIX = "calcifer.immediate_reconciler.";
    private static final String METRIC_COMMANDS_SENT = METRIC_PREFIX + "commands.sent";
    private static final String METRIC_COMMANDS_DEBOUNCED = METRIC_PREFIX + "commands.debounced";
    private static final String METRIC_COMMANDS_SKIPPED_CONVERGED = METRIC_PREFIX + "commands.skipped.converged";
    private static final String METRIC_COMMANDS_SKIPPED_UNHEALTHY = METRIC_PREFIX + "commands.skipped.unhealthy";

    private final DeviceStateRepository deviceStateRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final InfrastructureHealthGate infrastructureHealthGate;
    private final long debounceMs;

    // Metrics
    private final Counter commandsSentCounter;
    private final Counter commandsDebouncedCounter;
    private final Counter commandsSkippedConvergedCounter;
    private final Counter commandsSkippedUnhealthyCounter;

    // Debounce state: deviceKey -> pending future
    private final ConcurrentHashMap<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                final var thread = new Thread(r, "immediate-reconciler-debounce");
                thread.setDaemon(true);
                return thread;
            }
    );

    public ImmediateReconciler(
            final DeviceStateRepository deviceStateRepository,
            final ApplicationEventPublisher eventPublisher,
            final InfrastructureHealthGate infrastructureHealthGate,
            final MeterRegistry meterRegistry,
            @Value("${app.reconciliation.debounce-ms:50}") final long debounceMs
    ) {
        this.deviceStateRepository = deviceStateRepository;
        this.eventPublisher = eventPublisher;
        this.infrastructureHealthGate = infrastructureHealthGate;
        this.debounceMs = debounceMs;

        // Initialize metrics
        this.commandsSentCounter = Counter.builder(METRIC_COMMANDS_SENT)
                .description("Number of immediate commands sent")
                .register(meterRegistry);
        this.commandsDebouncedCounter = Counter.builder(METRIC_COMMANDS_DEBOUNCED)
                .description("Number of commands debounced (replaced by newer)")
                .register(meterRegistry);
        this.commandsSkippedConvergedCounter = Counter.builder(METRIC_COMMANDS_SKIPPED_CONVERGED)
                .description("Number of commands skipped because device already converged")
                .register(meterRegistry);
        this.commandsSkippedUnhealthyCounter = Counter.builder(METRIC_COMMANDS_SKIPPED_UNHEALTHY)
                .description("Number of commands skipped due to unhealthy infrastructure")
                .register(meterRegistry);

        log.info("ImmediateReconciler initialized with debounce window: {}ms", debounceMs);
    }

    /**
     * Handles DesiredStateCalculatedEvent by scheduling an immediate command with debounce.
     * If another event arrives for the same device within the debounce window,
     * the previous command is cancelled and replaced with the new one.
     *
     * @param event the desired state calculated event
     */
    @Async
    @EventListener
    public void onDesiredStateCalculated(final DesiredStateCalculatedEvent event) {
        final var desiredState = event.getDesiredState();
        final var deviceId = desiredState.id();
        final var deviceKey = formatDeviceKey(deviceId);

        log.debug("Received DesiredStateCalculatedEvent for device {}, scheduling with {}ms debounce",
                deviceKey, debounceMs);

        scheduleCommand(deviceKey, desiredState);
    }

    private void scheduleCommand(final String deviceKey, final DesiredDeviceState desiredState) {
        // Cancel any pending command for this device
        final var existing = pendingCommands.get(deviceKey);
        if (existing != null && existing.future() != null && !existing.future().isDone()) {
            existing.future().cancel(false);
            commandsDebouncedCounter.increment();
            log.trace("Debounced previous command for device {}", deviceKey);
        }

        // Schedule new command after debounce window
        final var future = debounceScheduler.schedule(
                () -> executeCommand(deviceKey, desiredState),
                debounceMs,
                TimeUnit.MILLISECONDS
        );

        pendingCommands.put(deviceKey, new PendingCommand(desiredState, future, Instant.now()));
    }

    private void executeCommand(final String deviceKey, final DesiredDeviceState desiredState) {
        try {
            // Remove from pending
            pendingCommands.remove(deviceKey);

            // Fail-stop: skip if infrastructure is unhealthy
            if (!infrastructureHealthGate.isHealthy()) {
                log.warn("ImmediateReconciler SKIPPED for device {}: infrastructure unhealthy", deviceKey);
                commandsSkippedUnhealthyCounter.increment();
                return;
            }

            // Check convergence before sending command
            final var snapshotOpt = deviceStateRepository.findTwinSnapshot(desiredState.id());
            if (snapshotOpt.isPresent() && snapshotOpt.get().isConverged()) {
                log.trace("ImmediateReconciler SKIPPED for device {}: already converged", deviceKey);
                commandsSkippedConvergedCounter.increment();
                return;
            }

            // Extract raw value and send command
            final var rawValue = extractRawValue(desiredState.value());
            final var commandEvent = new DeviceCommandEvent(
                    desiredState.id(),
                    desiredState.type(),
                    rawValue
            );

            eventPublisher.publishEvent(commandEvent);
            commandsSentCounter.increment();
            log.debug("ImmediateReconciler sent command for device {}: {}", deviceKey, rawValue);

        } catch (Exception e) {
            log.error("ImmediateReconciler failed for device {}: {}", deviceKey, e.getMessage(), e);
        }
    }

    private Object extractRawValue(final DeviceValue value) {
        return switch (value) {
            case RelayValue rv -> rv.state();
            case FanValue fv -> fv.speed();
        };
    }

    private String formatDeviceKey(final DeviceId deviceId) {
        return deviceId.controllerId() + ":" + deviceId.componentId();
    }

    private record PendingCommand(
            DesiredDeviceState desiredState,
            ScheduledFuture<?> future,
            Instant scheduledAt
    ) {
    }
}

