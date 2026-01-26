package dev.dmgiangi.core.server.domain.model.event;

import dev.dmgiangi.core.server.domain.model.UserIntent;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Domain event published when a user's intent for a device state changes.
 * This event is triggered when the API receives a new state request from the user.
 *
 * <p>Listeners can react to this event to:
 * <ul>
 *   <li>Trigger the DeviceLogicService to recalculate the desired state</li>
 *   <li>Log or audit intent changes</li>
 *   <li>Update UI/WebSocket subscribers</li>
 * </ul>
 */
public class UserIntentChangedEvent extends ApplicationEvent {

    @Getter
    private final UserIntent intent;

    public UserIntentChangedEvent(Object source, UserIntent intent) {
        super(source);
        this.intent = intent;
    }
}

