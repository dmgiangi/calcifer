package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.event.DesiredStateCalculatedEvent;
import dev.dmgiangi.core.server.domain.model.event.ReportedStateChangedEvent;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceLogicService;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link DeviceLogicService}.
 *
 * <p>Phase 1 implementation uses passthrough logic: Desired = Intent.
 * This validates the event-driven architecture without complex business rules.
 *
 * <p>Reacts to:
 * <ul>
 *   <li>{@link UserIntentChangedEvent} - User changed their intent via API</li>
 *   <li>{@link ReportedStateChangedEvent} - Device reported its actual state via MQTT</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDeviceLogicService implements DeviceLogicService {

    private final DeviceStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Handles user intent changes by recalculating the desired state.
     *
     * @param event the user intent changed event
     */
    @EventListener
    public void onUserIntentChanged(UserIntentChangedEvent event) {
        final var intent = event.getIntent();
        log.debug("Received UserIntentChangedEvent for device {}", intent.id());
        recalculateDesiredState(intent.id());
    }

    /**
     * Handles reported state changes by recalculating the desired state.
     * In Phase 1 (passthrough), this won't change the desired state,
     * but it's wired up for Phase 4 when business rules consider reported state.
     *
     * @param event the reported state changed event
     */
    @EventListener
    public void onReportedStateChanged(ReportedStateChangedEvent event) {
        final var reportedState = event.getReportedState();
        log.debug("Received ReportedStateChangedEvent for device {}", reportedState.id());
        recalculateDesiredState(reportedState.id());
    }

    @Override
    public void recalculateDesiredState(DeviceId id) {
        log.debug("Recalculating desired state for device {}", id);

        final var snapshotOpt = repository.findTwinSnapshot(id);
        if (snapshotOpt.isEmpty()) {
            log.warn("Cannot recalculate: no twin snapshot found for device {}", id);
            return;
        }

        final var snapshot = snapshotOpt.get();
        final var newDesired = calculateDesired(snapshot);

        if (newDesired == null) {
            log.debug("No desired state calculated for device {} (no intent)", id);
            return;
        }

        repository.saveDesiredState(newDesired);
        log.info("Saved new desired state for device {}: {}", id, newDesired.value());

        eventPublisher.publishEvent(new DesiredStateCalculatedEvent(this, newDesired));
    }

    /**
     * Phase 1 implementation: Passthrough logic.
     * Simply converts the user intent to a desired state.
     *
     * <p>Future phases will incorporate:
     * <ul>
     *   <li>Reported state comparison</li>
     *   <li>Business rules (e.g., safety limits, schedules)</li>
     *   <li>Conflict resolution strategies</li>
     * </ul>
     */
    @Override
    public DesiredDeviceState calculateDesired(DeviceTwinSnapshot snapshot) {
        final var intent = snapshot.intent();

        if (intent == null) {
            log.trace("No intent for device {}, cannot calculate desired state", snapshot.id());
            return null;
        }

        // Phase 1: Passthrough - Desired = Intent
        return new DesiredDeviceState(
            intent.id(),
            intent.type(),
            intent.value()
        );
    }
}

