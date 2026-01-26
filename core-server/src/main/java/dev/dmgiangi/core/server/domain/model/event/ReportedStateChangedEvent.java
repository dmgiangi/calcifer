package dev.dmgiangi.core.server.domain.model.event;

import dev.dmgiangi.core.server.domain.model.ReportedDeviceState;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Domain event published when a device reports its actual state via MQTT feedback.
 * This event is triggered when the infrastructure receives and processes actuator feedback.
 *
 * <p>Listeners can react to this event to:
 * <ul>
 *   <li>Trigger the DeviceLogicService to recalculate the desired state</li>
 *   <li>Detect convergence/divergence between reported and desired states</li>
 *   <li>Update UI/WebSocket subscribers with real device state</li>
 *   <li>Log state changes for debugging or auditing</li>
 * </ul>
 */
public class ReportedStateChangedEvent extends ApplicationEvent {

    @Getter
    private final ReportedDeviceState reportedState;

    public ReportedStateChangedEvent(Object source, ReportedDeviceState reportedState) {
        super(source);
        this.reportedState = reportedState;
    }
}

