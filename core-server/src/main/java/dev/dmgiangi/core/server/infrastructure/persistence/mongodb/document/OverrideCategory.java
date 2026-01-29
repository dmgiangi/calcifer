package dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document;

/**
 * Override category with fixed precedence per Phase 0.5.
 * Higher ordinal = higher priority.
 *
 * <p>Precedence: EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL
 *
 * <p>Note: HARDCODED_SAFETY and SYSTEM_SAFETY are Rule Categories (not Override Categories)
 * and are handled by SafetyRuleEngine. They cannot be overridden.
 */
public enum OverrideCategory {
    /**
     * User-initiated manual override (lowest priority)
     */
    MANUAL,

    /**
     * Scheduled override (e.g., vacation mode, night mode)
     */
    SCHEDULED,

    /**
     * Maintenance override (e.g., technician working on system)
     */
    MAINTENANCE,

    /**
     * Emergency override (highest priority, e.g., fire alarm)
     */
    EMERGENCY;

    /**
     * Returns true if this category has higher priority than the other.
     */
    public boolean hasHigherPriorityThan(final OverrideCategory other) {
        return this.ordinal() > other.ordinal();
    }

    /**
     * Returns true if this category has equal or higher priority than the other.
     */
    public boolean hasEqualOrHigherPriorityThan(final OverrideCategory other) {
        return this.ordinal() >= other.ordinal();
    }
}

