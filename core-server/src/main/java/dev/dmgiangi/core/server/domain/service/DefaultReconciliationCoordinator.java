package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.event.DesiredStateCalculatedEvent;
import dev.dmgiangi.core.server.domain.port.DecisionAuditRepository;
import dev.dmgiangi.core.server.domain.port.DecisionAuditRepository.AuditEntryData;
import dev.dmgiangi.core.server.domain.port.DecisionAuditRepository.DecisionType;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import dev.dmgiangi.core.server.infrastructure.health.InfrastructureHealthGate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link ReconciliationCoordinator}.
 * Orchestrates the reconciliation process with side effects.
 *
 * <p>Implements fail-stop pattern per Phase 0.3:
 * If infrastructure is unhealthy, reconciliation is skipped and devices fail-safe autonomously.
 */
@Slf4j
@Service
public class DefaultReconciliationCoordinator implements ReconciliationCoordinator {

    private static final String METRIC_PREFIX = "calcifer.reconciliation.";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String ACTOR_RECONCILIATION = "reconciliation-coordinator";

    private final StateCalculator stateCalculator;
    private final DeviceStateRepository deviceStateRepository;
    private final FunctionalSystemRepository functionalSystemRepository;
    private final DecisionAuditRepository decisionAuditRepository;
    private final InfrastructureHealthGate infrastructureHealthGate;
    private final ApplicationEventPublisher eventPublisher;

    // Metrics
    private final Counter successCounter;
    private final Counter noChangeCounter;
    private final Counter safetyRefusedCounter;
    private final Counter errorCounter;
    private final Timer reconciliationTimer;

    public DefaultReconciliationCoordinator(
            final StateCalculator stateCalculator,
            final DeviceStateRepository deviceStateRepository,
            final FunctionalSystemRepository functionalSystemRepository,
            final DecisionAuditRepository decisionAuditRepository,
            final InfrastructureHealthGate infrastructureHealthGate,
            final ApplicationEventPublisher eventPublisher,
            final MeterRegistry meterRegistry
    ) {
        this.stateCalculator = stateCalculator;
        this.deviceStateRepository = deviceStateRepository;
        this.functionalSystemRepository = functionalSystemRepository;
        this.decisionAuditRepository = decisionAuditRepository;
        this.infrastructureHealthGate = infrastructureHealthGate;
        this.eventPublisher = eventPublisher;

        // Initialize metrics
        this.successCounter = Counter.builder(METRIC_PREFIX + "success")
                .description("Successful reconciliations")
                .register(meterRegistry);
        this.noChangeCounter = Counter.builder(METRIC_PREFIX + "no_change")
                .description("Reconciliations with no change (no intent)")
                .register(meterRegistry);
        this.safetyRefusedCounter = Counter.builder(METRIC_PREFIX + "safety_refused")
                .description("Reconciliations refused by safety rules")
                .register(meterRegistry);
        this.errorCounter = Counter.builder(METRIC_PREFIX + "error")
                .description("Reconciliation errors")
                .register(meterRegistry);
        this.reconciliationTimer = Timer.builder(METRIC_PREFIX + "duration")
                .description("Time taken for reconciliation")
                .register(meterRegistry);
    }

    @Override
    public ReconciliationResult reconcile(final DeviceId deviceId) {
        return reconcile(deviceId, Map.of());
    }

    @Override
    public ReconciliationResult reconcile(final DeviceId deviceId, final Map<String, Object> metadata) {
        Objects.requireNonNull(deviceId, "Device ID must not be null");

        return reconciliationTimer.record(() -> {
            // Fail-stop: check infrastructure health first
            if (!infrastructureHealthGate.isHealthy()) {
                log.warn("Reconciliation SKIPPED for device {}: infrastructure unhealthy", deviceId);
                return ReconciliationResult.infrastructureUnavailable(
                        "Infrastructure unhealthy - reconciliation halted for safety");
            }

            try {
                // Load device snapshot
                final var snapshotOpt = deviceStateRepository.findTwinSnapshot(deviceId);
                if (snapshotOpt.isEmpty()) {
                    log.warn("Reconciliation SKIPPED for device {}: snapshot not found", deviceId);
                    return ReconciliationResult.deviceNotFound(deviceId);
                }

                // Load FunctionalSystem if device belongs to one
                final var deviceKey = deviceId.controllerId() + ":" + deviceId.componentId();
                final var systemOpt = functionalSystemRepository.findByDeviceId(deviceKey);

                return reconcile(snapshotOpt.get(), systemOpt.orElse(null), metadata);

            } catch (Exception e) {
                log.error("Reconciliation failed for device {}: {}", deviceId, e.getMessage(), e);
                errorCounter.increment();
                return ReconciliationResult.error("Reconciliation failed: " + e.getMessage());
            }
        });
    }

    @Override
    public ReconciliationResult reconcile(
            final DeviceTwinSnapshot snapshot,
            final FunctionalSystemData system,
            final Map<String, Object> metadata
    ) {
        Objects.requireNonNull(snapshot, "Snapshot must not be null");

        final var deviceId = snapshot.id();
        final var systemId = system != null ? system.id() : null;
        log.debug("Reconciling device {} (system: {})", deviceId, systemId != null ? systemId : "standalone");

        // Get previous desired value for audit
        final var previousValue = snapshot.desired() != null ? snapshot.desired().value() : null;

        // Calculate desired state via pure function
        final var calcResult = stateCalculator.calculateWithDetails(
                snapshot, system, metadata != null ? metadata : Map.of());

        return processCalculationResult(deviceId, systemId, previousValue, calcResult);
    }

    private ReconciliationResult processCalculationResult(
            final DeviceId deviceId,
            final String systemId,
            final Object previousValue,
            final StateCalculator.CalculationResult calcResult
    ) {
        final var deviceIdStr = formatDeviceId(deviceId);
        final var correlationId = MDC.get(MDC_TRACE_ID);

        return switch (calcResult.source()) {
            case INTENT, OVERRIDE, SAFETY_MODIFIED -> {
                // State calculated - persist and publish event
                final var desiredState = calcResult.desiredState();
                deviceStateRepository.saveDesiredState(desiredState);
                eventPublisher.publishEvent(new DesiredStateCalculatedEvent(this, desiredState));

                // Audit log the decision
                auditDecision(
                        correlationId, deviceIdStr, systemId,
                        mapSourceToDecisionType(calcResult.source()),
                        previousValue, desiredState.value(),
                        calcResult.reason(),
                        Map.of("source", calcResult.source().name())
                );

                log.info("Reconciliation SUCCESS for device {}: {} (source: {})",
                        deviceId, desiredState.value(), calcResult.source());
                successCounter.increment();
                yield ReconciliationResult.success(desiredState, calcResult);
            }

            case SAFETY_REFUSED -> {
                // Audit log the refusal
                auditDecision(
                        correlationId, deviceIdStr, systemId,
                        DecisionType.SAFETY_RULE_ACTIVATED,
                        previousValue, null,
                        calcResult.reason(),
                        Map.of("source", calcResult.source().name(), "refused", true)
                );

                log.warn("Reconciliation REFUSED for device {}: {}", deviceId, calcResult.reason());
                safetyRefusedCounter.increment();
                yield ReconciliationResult.safetyRefused(calcResult, calcResult.reason());
            }

            case NO_VALUE -> {
                // NO_VALUE: no override or intent available - no audit needed for no-op
                log.debug("Reconciliation NO_CHANGE for device {}: {}", deviceId, calcResult.reason());
                noChangeCounter.increment();
                yield ReconciliationResult.noChange(calcResult, calcResult.reason());
            }
        };
    }

    private void auditDecision(
            final String correlationId,
            final String deviceId,
            final String systemId,
            final DecisionType decisionType,
            final Object previousValue,
            final Object newValue,
            final String reason,
            final Map<String, Object> context
    ) {
        try {
            final var auditEntry = AuditEntryData.create(
                    correlationId,
                    deviceId,
                    systemId,
                    decisionType,
                    ACTOR_RECONCILIATION,
                    previousValue,
                    newValue,
                    reason,
                    context
            );
            decisionAuditRepository.save(auditEntry);
            log.trace("Audit entry saved for device {}: {}", deviceId, decisionType);
        } catch (Exception e) {
            // Audit logging should not fail the reconciliation
            log.warn("Failed to save audit entry for device {}: {}", deviceId, e.getMessage());
        }
    }

    private DecisionType mapSourceToDecisionType(final StateCalculator.CalculationResult.ValueSource source) {
        return switch (source) {
            case INTENT -> DecisionType.DESIRED_CALCULATED;
            case OVERRIDE -> DecisionType.OVERRIDE_APPLIED;
            case SAFETY_MODIFIED -> DecisionType.SAFETY_RULE_ACTIVATED;
            case SAFETY_REFUSED -> DecisionType.SAFETY_RULE_ACTIVATED;
            case NO_VALUE -> DecisionType.DESIRED_CALCULATED;
        };
    }

    private String formatDeviceId(final DeviceId deviceId) {
        return deviceId.controllerId() + ":" + deviceId.componentId();
    }
}

