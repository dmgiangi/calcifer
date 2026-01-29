package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.event.ReportedStateChangedEvent;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceLogicService;
import dev.dmgiangi.core.server.domain.service.ReconciliationCoordinator.ReconciliationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Default implementation of {@link DeviceLogicService}.
 * Acts as a facade that composes the three extracted components:
 * <ul>
 *   <li>{@link StateCalculator} - Pure function for state calculation</li>
 *   <li>{@link SafetyValidator} - Safety rule validation</li>
 *   <li>{@link ReconciliationCoordinator} - Side-effect orchestration</li>
 * </ul>
 *
 * <p>Reacts to:
 * <ul>
 *   <li>{@link UserIntentChangedEvent} - User changed their intent via API</li>
 *   <li>{@link ReportedStateChangedEvent} - Device reported its actual state via MQTT</li>
 * </ul>
 *
 * <p>Per Phase 3 refactoring: This class now delegates to ReconciliationCoordinator
 * for the full reconciliation flow (load, calculate, persist, publish).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDeviceLogicService implements DeviceLogicService {

    private final ReconciliationCoordinator reconciliationCoordinator;
    private final StateCalculator stateCalculator;

    /**
     * Handles user intent changes by triggering reconciliation.
     * Executed asynchronously to avoid blocking the event publisher.
     *
     * @param event the user intent changed event
     */
    @Async
    @EventListener
    public void onUserIntentChanged(UserIntentChangedEvent event) {
        final var intent = event.getIntent();
        log.debug("Received UserIntentChangedEvent for device {}", intent.id());
        recalculateDesiredState(intent.id());
    }

    /**
     * Handles reported state changes by triggering reconciliation.
     * Executed asynchronously to avoid blocking the event publisher.
     *
     * @param event the reported state changed event
     */
    @Async
    @EventListener
    public void onReportedStateChanged(ReportedStateChangedEvent event) {
        final var reportedState = event.getReportedState();
        log.debug("Received ReportedStateChangedEvent for device {}", reportedState.id());
        recalculateDesiredState(reportedState.id());
    }

    /**
     * Recalculates the desired state for a device by delegating to ReconciliationCoordinator.
     * The coordinator handles the full flow: load snapshot, calculate, persist, publish event.
     *
     * @param id the device identifier to recalculate
     */
    @Override
    public void recalculateDesiredState(DeviceId id) {
        log.debug("Recalculating desired state for device {}", id);

        final var result = reconciliationCoordinator.reconcile(id, Map.of());

        logReconciliationResult(id, result);
    }

    /**
     * Calculates the desired state based on user intent and safety rules.
     * Delegates to StateCalculator for the pure calculation.
     *
     * <p>Note: This method is kept for backward compatibility and testing.
     * For full reconciliation with persistence and events, use {@link #recalculateDesiredState(DeviceId)}.
     *
     * @param snapshot the complete device twin snapshot
     * @return the calculated desired state, or null if no intent exists or safety refused
     */
    @Override
    public DesiredDeviceState calculateDesired(DeviceTwinSnapshot snapshot) {
        // Delegate to StateCalculator (pure function)
        // Note: This bypasses FunctionalSystem context - use reconciliationCoordinator for full flow
        return stateCalculator.calculate(snapshot, null, Map.of()).orElse(null);
    }

    /**
     * Logs the reconciliation result with appropriate log level.
     */
    private void logReconciliationResult(DeviceId id, ReconciliationResult result) {
        switch (result.outcome()) {
            case SUCCESS -> log.info("Reconciliation SUCCESS for device {}: {}",
                    id, result.desiredState().value());
            case NO_CHANGE -> log.debug("Reconciliation NO_CHANGE for device {}: {}",
                    id, result.reason());
            case SAFETY_REFUSED -> log.warn("Reconciliation REFUSED for device {}: {}",
                    id, result.reason());
            case DEVICE_NOT_FOUND -> log.warn("Reconciliation SKIPPED for device {}: not found",
                    id);
            case INFRASTRUCTURE_UNAVAILABLE -> log.warn("Reconciliation HALTED for device {}: {}",
                    id, result.reason());
            case ERROR -> log.error("Reconciliation ERROR for device {}: {}",
                    id, result.reason());
        }
    }
}

