package dev.dmgiangi.core.server.domain.model.event;

import dev.dmgiangi.core.server.domain.model.ActuatorFeedback;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Domain event published when raw actuator feedback is received from MQTT.
 * This event contains the raw feedback data before parsing into DeviceValue.
 *
 * <p>Listeners can react to this event to:
 * <ul>
 *   <li>Parse the raw value into the appropriate DeviceValue</li>
 *   <li>Update the ReportedDeviceState in the repository</li>
 *   <li>Trigger downstream state recalculation</li>
 * </ul>
 */
public class ActuatorFeedbackReceivedEvent extends ApplicationEvent {

    @Getter
    private final ActuatorFeedback feedback;

    public ActuatorFeedbackReceivedEvent(Object source, ActuatorFeedback feedback) {
        super(source);
        this.feedback = feedback;
    }
}

