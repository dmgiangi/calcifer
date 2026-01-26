package dev.dmgiangi.core.server.domain.model.event;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Domain event published when the DeviceLogicService calculates a new desired state.
 * This event is triggered after processing a UserIntentChangedEvent or ReportedStateChangedEvent.
 *
 * <p>The desired state is the result of applying business rules:
 * <pre>
 * Desired = f(UserIntent, ReportedState, BusinessRules)
 * </pre>
 *
 * <p>Listeners can react to this event to:
 * <ul>
 *   <li>Persist the new desired state to the repository</li>
 *   <li>Trigger immediate reconciliation (bypass scheduled interval)</li>
 *   <li>Update UI/WebSocket subscribers</li>
 *   <li>Log state calculations for debugging</li>
 * </ul>
 */
public class DesiredStateCalculatedEvent extends ApplicationEvent {

    @Getter
    private final DesiredDeviceState desiredState;

    public DesiredStateCalculatedEvent(Object source, DesiredDeviceState desiredState) {
        super(source);
        this.desiredState = desiredState;
    }
}

