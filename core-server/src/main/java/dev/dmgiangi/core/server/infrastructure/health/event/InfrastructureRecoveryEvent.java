package dev.dmgiangi.core.server.infrastructure.health.event;

import org.springframework.context.ApplicationEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Event published when critical infrastructure (Redis, MongoDB) recovers after a failure.
 * Consumers can use this to clear alerts and resume normal operations.
 *
 * <p>Per fail-stop pattern: when this event is published, command generation can resume.
 */
public class InfrastructureRecoveryEvent extends ApplicationEvent {

    private final InfrastructureComponent component;
    private final Instant recoveredAt;
    private final Duration downtime;

    public InfrastructureRecoveryEvent(final Object source,
                                       final InfrastructureComponent component,
                                       final Duration downtime) {
        super(source);
        this.component = component;
        this.recoveredAt = Instant.now();
        this.downtime = downtime;
    }

    public InfrastructureComponent getComponent() {
        return component;
    }

    public Instant getRecoveredAt() {
        return recoveredAt;
    }

    public Duration getDowntime() {
        return downtime;
    }

    @Override
    public String toString() {
        return "InfrastructureRecoveryEvent{component=%s, recoveredAt=%s, downtime=%s}"
                .formatted(component, recoveredAt, downtime);
    }
}

