package dev.dmgiangi.core.server.infrastructure.exception;

import dev.dmgiangi.core.server.infrastructure.health.event.InfrastructureComponent;

/**
 * Exception thrown when critical infrastructure is unavailable.
 *
 * <p>Per fail-stop pattern: when infrastructure is down, operations that depend on it
 * should fail immediately rather than using potentially stale fallback data.
 * This is safer for IoT systems where devices have their own hardware fail-safe mechanisms.
 */
public class InfrastructureUnavailableException extends RuntimeException {

    private final InfrastructureComponent component;

    public InfrastructureUnavailableException(final InfrastructureComponent component) {
        super("Infrastructure component '%s' is unavailable".formatted(component.name()));
        this.component = component;
    }

    public InfrastructureUnavailableException(final InfrastructureComponent component, final String message) {
        super("Infrastructure component '%s' is unavailable: %s".formatted(component.name(), message));
        this.component = component;
    }

    public InfrastructureUnavailableException(final InfrastructureComponent component, final Throwable cause) {
        super("Infrastructure component '%s' is unavailable".formatted(component.name()), cause);
        this.component = component;
    }

    public InfrastructureComponent getComponent() {
        return component;
    }
}

