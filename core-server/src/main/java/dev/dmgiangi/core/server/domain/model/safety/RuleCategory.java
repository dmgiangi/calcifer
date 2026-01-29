package dev.dmgiangi.core.server.domain.model.safety;

/**
 * Rule categories with fixed precedence per Phase 0.4.
 * Higher ordinal = higher priority.
 *
 * <p>Precedence order (highest to lowest):
 * <ol>
 *   <li>HARDCODED_SAFETY - Java-based critical safety rules, cannot be overridden</li>
 *   <li>SYSTEM_SAFETY - System-level safety rules from configuration, cannot be overridden</li>
 *   <li>EMERGENCY - Emergency overrides (e.g., fire alarm)</li>
 *   <li>MAINTENANCE - Maintenance overrides (e.g., technician working)</li>
 *   <li>SCHEDULED - Scheduled overrides (e.g., vacation mode)</li>
 *   <li>MANUAL - User-initiated manual overrides</li>
 *   <li>USER_INTENT - Normal user intent (lowest priority)</li>
 * </ol>
 *
 * <p>Note: HARDCODED_SAFETY and SYSTEM_SAFETY rules are always evaluated,
 * even when SpEL engine fails (per Phase 0.3 layered resilience).
 */
public enum RuleCategory {
    /**
     * Normal user intent (lowest priority).
     * This is the baseline - what the user wants the device to do.
     */
    USER_INTENT,

    /**
     * User-initiated manual override.
     * Takes precedence over normal intent.
     */
    MANUAL,

    /**
     * Scheduled override (e.g., vacation mode, night mode).
     * Takes precedence over manual overrides.
     */
    SCHEDULED,

    /**
     * Maintenance override (e.g., technician working on system).
     * Takes precedence over scheduled overrides.
     */
    MAINTENANCE,

    /**
     * Emergency override (e.g., fire alarm, flood detection).
     * Takes precedence over maintenance overrides.
     */
    EMERGENCY,

    /**
     * System-level safety rules from configuration.
     * Cannot be overridden by any override category.
     * Evaluated from YAML/MongoDB configuration.
     */
    SYSTEM_SAFETY,

    /**
     * Hardcoded critical safety rules in Java.
     * Cannot be overridden by any means.
     * Always evaluated even when SpEL engine fails.
     * Example: "Pump ON → Fire OFF impossible"
     */
    HARDCODED_SAFETY;

    /**
     * Checks if this category can be overridden.
     * Per Phase 0.4: HARDCODED_SAFETY and SYSTEM_SAFETY cannot be overridden.
     *
     * @return true if this category can be overridden
     */
    public boolean isOverridable() {
        return this != HARDCODED_SAFETY && this != SYSTEM_SAFETY;
    }

    /**
     * Checks if this category is a safety category.
     *
     * @return true if this is HARDCODED_SAFETY or SYSTEM_SAFETY
     */
    public boolean isSafetyCategory() {
        return this == HARDCODED_SAFETY || this == SYSTEM_SAFETY;
    }

    /**
     * Compares precedence with another category.
     *
     * @param other the other category to compare
     * @return positive if this has higher precedence, negative if lower, 0 if equal
     */
    public int comparePrecedence(RuleCategory other) {
        return this.ordinal() - other.ordinal();
    }
}

