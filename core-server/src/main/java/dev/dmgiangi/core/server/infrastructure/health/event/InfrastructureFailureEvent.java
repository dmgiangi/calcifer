package dev.dmgiangi.core.server.infrastructure.health.event;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * Event published when critical infrastructure (Redis, MongoDB) becomes unavailable.
 * Consumers can use this for alerting (email, push notifications, dashboard).
 *
 * <p>Per fail-stop pattern: when this event is published, command generation is halted.
 * Devices will fail-safe to their hardware defaults autonomously.
 */
public class InfrastructureFailureEvent extends ApplicationEvent {

    private final InfrastructureComponent component;
    private final String errorMessage;
    private final Instant failedAt;

    public InfrastructureFailureEvent(final Object source,
                                      final InfrastructureComponent component,
                                      final String errorMessage) {
        super(source);
        this.component = component;
        this.errorMessage = errorMessage;
        this.failedAt = Instant.now();
    }

    public InfrastructureComponent getComponent() {
        return component;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    @Override
    public String toString() {
        return "InfrastructureFailureEvent{component=%s, errorMessage='%s', failedAt=%s}"
                .formatted(component, errorMessage, failedAt);
    }
}

