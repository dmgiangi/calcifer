package dev.dmgiangi.core.server.domain.model;

/**
 * Enumeration of supported FunctionalSystem types.
 * Each type represents a specific IoT subsystem with its own device composition
 * and safety rules.
 *
 * <p>Per Phase 0.2: FunctionalSystem is a DDD Aggregate Root that owns
 * CONFIGURATION and DEVICE MEMBERSHIP. The type determines:
 * <ul>
 *   <li>Expected device composition (which devices belong to the system)</li>
 *   <li>Applicable safety rules (hardcoded and configurable)</li>
 *   <li>Default fail-safe behavior</li>
 *   <li>Reconciliation strategy</li>
 * </ul>
 *
 * <p>System types:
 * <ul>
 *   <li><b>TERMOCAMINO</b> - Wood-burning fireplace with water heating.
 *       Devices: fire relay, pump relay, temperature sensors.
 *       Critical safety: pump must run when fire is on (overheating protection).</li>
 *   <li><b>HVAC</b> - Heating, Ventilation, and Air Conditioning.
 *       Devices: fans, relays, temperature sensors.
 *       Safety: fan speed limits, temperature thresholds.</li>
 *   <li><b>IRRIGATION</b> - Garden/agricultural irrigation system.
 *       Devices: valve relays, soil moisture sensors, rain sensors.
 *       Safety: water conservation rules, freeze protection.</li>
 *   <li><b>GENERIC</b> - Custom system without predefined rules.
 *       For user-defined device groupings with custom safety rules.</li>
 * </ul>
 */
public enum FunctionalSystemType {

    /**
     * Wood-burning fireplace with water heating system.
     * Critical safety interlock: pump must run when fire is active.
     */
    TERMOCAMINO("Termocamino", "Wood-burning fireplace with water heating"),

    /**
     * Heating, Ventilation, and Air Conditioning system.
     * Manages climate control with fans, heaters, and temperature sensors.
     */
    HVAC("HVAC", "Heating, Ventilation, and Air Conditioning"),

    /**
     * Irrigation system for gardens or agriculture.
     * Manages water distribution with valves and moisture sensors.
     */
    IRRIGATION("Irrigation", "Garden or agricultural irrigation system"),

    /**
     * Generic system for custom device groupings.
     * No predefined safety rules - relies on user-configured rules.
     */
    GENERIC("Generic", "Custom system without predefined rules");

    private final String displayName;
    private final String description;

    FunctionalSystemType(final String displayName, final String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Returns the human-readable display name.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the description of this system type.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if this system type has critical safety interlocks.
     * Critical systems require hardcoded safety rules that cannot be disabled.
     *
     * @return true if this system type has critical safety requirements
     */
    public boolean hasCriticalSafetyInterlocks() {
        return this == TERMOCAMINO;
    }

    /**
     * Checks if this system type requires temperature monitoring.
     *
     * @return true if temperature sensors are expected
     */
    public boolean requiresTemperatureMonitoring() {
        return this == TERMOCAMINO || this == HVAC;
    }

    /**
     * Checks if this system type is water-related.
     *
     * @return true if the system manages water flow
     */
    public boolean isWaterRelated() {
        return this == TERMOCAMINO || this == IRRIGATION;
    }

    /**
     * Returns the default fail-safe mode for this system type.
     * Per Phase 0.3: Fail-safe defaults are applied when fallbacks fail.
     *
     * @return description of the default fail-safe behavior
     */
    public String getDefaultFailSafeMode() {
        return switch (this) {
            case TERMOCAMINO -> "PUMP_ON_FIRE_OFF"; // Keep pump running, turn off fire
            case HVAC -> "ALL_OFF"; // Turn off all HVAC components
            case IRRIGATION -> "VALVES_CLOSED"; // Close all water valves
            case GENERIC -> "MAINTAIN_LAST_STATE"; // Keep last known safe state
        };
    }
}

