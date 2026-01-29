package dev.dmgiangi.core.server.domain.model;

/**
 * Enumeration of operational modes for FunctionalSystem.
 * Determines how the system responds to inputs and calculates desired states.
 *
 * <p>Mode semantics:
 * <ul>
 *   <li><b>OFF</b> - System is disabled, all devices in safe state</li>
 *   <li><b>MANUAL</b> - User controls each device directly via UserIntent</li>
 *   <li><b>AUTO</b> - System calculates desired states based on sensors and rules</li>
 *   <li><b>ECO</b> - Energy-saving mode with reduced targets and schedules</li>
 *   <li><b>BOOST</b> - Maximum performance mode, ignores schedules</li>
 *   <li><b>AWAY</b> - Minimal operation for unoccupied periods</li>
 * </ul>
 */
public enum OperationalMode {

    /**
     * System is disabled. All devices should be in their safe/off state.
     * Safety rules still apply (e.g., pump may run if fire is hot).
     */
    OFF("Off", "System disabled, devices in safe state"),

    /**
     * Manual control mode. User controls each device directly.
     * System does not auto-calculate desired states.
     */
    MANUAL("Manual", "User controls devices directly"),

    /**
     * Automatic mode. System calculates desired states based on
     * sensor readings, schedules, and configured rules.
     */
    AUTO("Automatic", "System calculates states automatically"),

    /**
     * Energy-saving mode. Reduced targets and strict schedule adherence.
     * May lower target temperatures or reduce fan speeds.
     */
    ECO("Eco", "Energy-saving mode with reduced targets"),

    /**
     * Maximum performance mode. Ignores schedules, uses maximum targets.
     * For rapid heating/cooling or emergency situations.
     */
    BOOST("Boost", "Maximum performance, ignores schedules"),

    /**
     * Away mode. Minimal operation for unoccupied periods.
     * Maintains minimum safe conditions (frost protection, etc.).
     */
    AWAY("Away", "Minimal operation for unoccupied periods");

    private final String displayName;
    private final String description;

    OperationalMode(final String displayName, final String description) {
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
     * Returns the description of this mode.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if this mode allows automatic state calculation.
     *
     * @return true if the system should auto-calculate desired states
     */
    public boolean isAutomatic() {
        return this == AUTO || this == ECO || this == BOOST || this == AWAY;
    }

    /**
     * Checks if this mode respects schedules.
     *
     * @return true if schedules should be applied
     */
    public boolean respectsSchedule() {
        return this == AUTO || this == ECO || this == AWAY;
    }

    /**
     * Checks if this mode is an active operational mode.
     *
     * @return true if the system should be actively controlling devices
     */
    public boolean isActive() {
        return this != OFF;
    }
}

