package dev.dmgiangi.core.server.domain.model;

/**
 * Value object representing safety thresholds for a FunctionalSystem.
 * These thresholds are used by safety rules to determine when to intervene.
 *
 * <p>Per Phase 0.4: Safety thresholds are part of system configuration and
 * are evaluated by both hardcoded and configurable safety rules.
 *
 * <p>All temperatures are in Celsius. All thresholds are optional (nullable)
 * to support different system types with different requirements.
 *
 * @param maxTemperature      maximum allowed temperature before safety intervention
 * @param minTemperature      minimum allowed temperature (frost protection)
 * @param criticalTemperature temperature that triggers emergency shutdown
 * @param maxFanSpeed         maximum allowed fan speed (0-4 scale)
 * @param hysteresis          temperature hysteresis to prevent oscillation
 */
public record SafetyThresholds(
        Double maxTemperature,
        Double minTemperature,
        Double criticalTemperature,
        Integer maxFanSpeed,
        Double hysteresis
) {

    /**
     * Default hysteresis value in Celsius.
     */
    public static final double DEFAULT_HYSTERESIS = 2.0;

    /**
     * Default maximum fan speed.
     */
    public static final int DEFAULT_MAX_FAN_SPEED = 4;

    /**
     * Compact constructor with validation.
     */
    public SafetyThresholds {
        // Validate temperature ordering if both are set
        if (minTemperature != null && maxTemperature != null && minTemperature >= maxTemperature) {
            throw new IllegalArgumentException("minTemperature must be less than maxTemperature");
        }
        if (maxTemperature != null && criticalTemperature != null && maxTemperature >= criticalTemperature) {
            throw new IllegalArgumentException("maxTemperature must be less than criticalTemperature");
        }
        if (maxFanSpeed != null && (maxFanSpeed < 0 || maxFanSpeed > 4)) {
            throw new IllegalArgumentException("maxFanSpeed must be between 0 and 4");
        }
        if (hysteresis != null && hysteresis < 0) {
            throw new IllegalArgumentException("hysteresis must be non-negative");
        }
    }

    /**
     * Creates default safety thresholds suitable for most systems.
     *
     * @return default SafetyThresholds
     */
    public static SafetyThresholds defaults() {
        return new SafetyThresholds(null, null, null, DEFAULT_MAX_FAN_SPEED, DEFAULT_HYSTERESIS);
    }

    /**
     * Creates safety thresholds for a TERMOCAMINO system.
     * Critical safety: water temperature must not exceed 85°C.
     *
     * @return SafetyThresholds configured for TERMOCAMINO
     */
    public static SafetyThresholds forTermocamino() {
        return new SafetyThresholds(
                80.0,   // maxTemperature - pump should run above this
                5.0,    // minTemperature - frost protection
                85.0,   // criticalTemperature - emergency shutdown
                DEFAULT_MAX_FAN_SPEED,
                DEFAULT_HYSTERESIS
        );
    }

    /**
     * Creates safety thresholds for an HVAC system.
     *
     * @return SafetyThresholds configured for HVAC
     */
    public static SafetyThresholds forHvac() {
        return new SafetyThresholds(
                35.0,   // maxTemperature - comfort limit
                15.0,   // minTemperature - comfort limit
                45.0,   // criticalTemperature - equipment protection
                DEFAULT_MAX_FAN_SPEED,
                1.0     // smaller hysteresis for comfort
        );
    }

    /**
     * Returns a copy with updated max temperature.
     *
     * @param newMaxTemperature the new max temperature
     * @return a new SafetyThresholds with updated value
     */
    public SafetyThresholds withMaxTemperature(final Double newMaxTemperature) {
        return new SafetyThresholds(newMaxTemperature, minTemperature, criticalTemperature, maxFanSpeed, hysteresis);
    }

    /**
     * Returns a copy with updated min temperature.
     *
     * @param newMinTemperature the new min temperature
     * @return a new SafetyThresholds with updated value
     */
    public SafetyThresholds withMinTemperature(final Double newMinTemperature) {
        return new SafetyThresholds(maxTemperature, newMinTemperature, criticalTemperature, maxFanSpeed, hysteresis);
    }

    /**
     * Returns a copy with updated critical temperature.
     *
     * @param newCriticalTemperature the new critical temperature
     * @return a new SafetyThresholds with updated value
     */
    public SafetyThresholds withCriticalTemperature(final Double newCriticalTemperature) {
        return new SafetyThresholds(maxTemperature, minTemperature, newCriticalTemperature, maxFanSpeed, hysteresis);
    }

    /**
     * Checks if a temperature exceeds the maximum threshold.
     *
     * @param temperature the temperature to check
     * @return true if temperature exceeds max (or max is not set)
     */
    public boolean exceedsMaxTemperature(final double temperature) {
        return maxTemperature != null && temperature > maxTemperature;
    }

    /**
     * Checks if a temperature is below the minimum threshold.
     *
     * @param temperature the temperature to check
     * @return true if temperature is below min (or min is not set)
     */
    public boolean belowMinTemperature(final double temperature) {
        return minTemperature != null && temperature < minTemperature;
    }

    /**
     * Checks if a temperature has reached critical level.
     *
     * @param temperature the temperature to check
     * @return true if temperature exceeds critical threshold
     */
    public boolean isCritical(final double temperature) {
        return criticalTemperature != null && temperature >= criticalTemperature;
    }

    /**
     * Gets the effective hysteresis value.
     *
     * @return the hysteresis value, or default if not set
     */
    public double getEffectiveHysteresis() {
        return hysteresis != null ? hysteresis : DEFAULT_HYSTERESIS;
    }

    /**
     * Gets the effective max fan speed.
     *
     * @return the max fan speed, or default if not set
     */
    public int getEffectiveMaxFanSpeed() {
        return maxFanSpeed != null ? maxFanSpeed : DEFAULT_MAX_FAN_SPEED;
    }
}

