package dev.dmgiangi.core.server.infrastructure.health.event;

/**
 * Enumeration of critical infrastructure components monitored by the health gate.
 */
public enum InfrastructureComponent {

    /**
     * Redis - used for real-time device state (UserIntent, ReportedState, DesiredState).
     * Critical for command generation.
     */
    REDIS,

    /**
     * MongoDB - used for configuration (FunctionalSystem, Overrides, Audit).
     * Critical for safety rules and override resolution.
     */
    MONGODB,

    /**
     * RabbitMQ - used for MQTT bridge and command delivery.
     * Critical for sending commands to devices.
     */
    RABBITMQ
}

