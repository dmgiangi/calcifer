package dev.dmgiangi.core.server.domain.model;

import java.time.LocalTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Value object representing the configuration of a FunctionalSystem.
 * Contains operational parameters like mode, target temperature, schedule, and safety thresholds.
 *
 * <p>Per Phase 0.2: FunctionalSystem owns CONFIGURATION. This value object provides
 * type-safe access to configuration parameters while supporting flexible extension
 * via the metadata map.
 *
 * <p>Immutable - all modifications return new instances.
 *
 * @param mode              the operational mode (e.g., AUTO, MANUAL, OFF, ECO)
 * @param targetTemperature the target temperature in Celsius (nullable for non-temperature systems)
 * @param scheduleStart     the daily schedule start time (nullable if no schedule)
 * @param scheduleEnd       the daily schedule end time (nullable if no schedule)
 * @param safetyThresholds  safety threshold values (e.g., maxTemp, minTemp, maxSpeed)
 * @param metadata          additional configuration parameters for extensibility
 */
public record SystemConfiguration(
        OperationalMode mode,
        Double targetTemperature,
        LocalTime scheduleStart,
        LocalTime scheduleEnd,
        SafetyThresholds safetyThresholds,
        Map<String, Object> metadata
) {

    /**
     * Compact constructor with validation and defensive copying.
     */
    public SystemConfiguration {
        Objects.requireNonNull(mode, "Operational mode must not be null");
        safetyThresholds = safetyThresholds != null ? safetyThresholds : SafetyThresholds.defaults();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();

        // Validate schedule consistency
        if ((scheduleStart == null) != (scheduleEnd == null)) {
            throw new IllegalArgumentException("Schedule start and end must both be set or both be null");
        }
    }

    /**
     * Creates a minimal configuration with just the mode.
     *
     * @param mode the operational mode
     * @return a new SystemConfiguration with defaults
     */
    public static SystemConfiguration ofMode(final OperationalMode mode) {
        return new SystemConfiguration(mode, null, null, null, SafetyThresholds.defaults(), Map.of());
    }

    /**
     * Creates a configuration for temperature-controlled systems.
     *
     * @param mode              the operational mode
     * @param targetTemperature the target temperature in Celsius
     * @return a new SystemConfiguration
     */
    public static SystemConfiguration forTemperatureControl(
            final OperationalMode mode,
            final double targetTemperature
    ) {
        return new SystemConfiguration(mode, targetTemperature, null, null, SafetyThresholds.defaults(), Map.of());
    }

    /**
     * Returns a copy with updated mode.
     *
     * @param newMode the new operational mode
     * @return a new SystemConfiguration with updated mode
     */
    public SystemConfiguration withMode(final OperationalMode newMode) {
        return new SystemConfiguration(newMode, targetTemperature, scheduleStart, scheduleEnd, safetyThresholds, metadata);
    }

    /**
     * Returns a copy with updated target temperature.
     *
     * @param newTargetTemperature the new target temperature
     * @return a new SystemConfiguration with updated target temperature
     */
    public SystemConfiguration withTargetTemperature(final Double newTargetTemperature) {
        return new SystemConfiguration(mode, newTargetTemperature, scheduleStart, scheduleEnd, safetyThresholds, metadata);
    }

    /**
     * Returns a copy with updated schedule.
     *
     * @param start the schedule start time
     * @param end   the schedule end time
     * @return a new SystemConfiguration with updated schedule
     */
    public SystemConfiguration withSchedule(final LocalTime start, final LocalTime end) {
        return new SystemConfiguration(mode, targetTemperature, start, end, safetyThresholds, metadata);
    }

    /**
     * Returns a copy with updated safety thresholds.
     *
     * @param newThresholds the new safety thresholds
     * @return a new SystemConfiguration with updated thresholds
     */
    public SystemConfiguration withSafetyThresholds(final SafetyThresholds newThresholds) {
        return new SystemConfiguration(mode, targetTemperature, scheduleStart, scheduleEnd, newThresholds, metadata);
    }

    /**
     * Checks if a schedule is configured.
     *
     * @return true if both schedule start and end are set
     */
    public boolean hasSchedule() {
        return scheduleStart != null && scheduleEnd != null;
    }

    /**
     * Checks if the current time is within the scheduled period.
     *
     * @param currentTime the current time to check
     * @return true if within schedule, false if no schedule or outside schedule
     */
    public boolean isWithinSchedule(final LocalTime currentTime) {
        if (!hasSchedule()) {
            return false;
        }
        // Handle overnight schedules (e.g., 22:00 to 06:00)
        if (scheduleStart.isAfter(scheduleEnd)) {
            return !currentTime.isBefore(scheduleStart) || !currentTime.isAfter(scheduleEnd);
        }
        return !currentTime.isBefore(scheduleStart) && !currentTime.isAfter(scheduleEnd);
    }

    /**
     * Returns the target temperature if set.
     *
     * @return Optional containing the target temperature, or empty if not set
     */
    public Optional<Double> getTargetTemperature() {
        return Optional.ofNullable(targetTemperature);
    }

    /**
     * Gets a metadata value by key.
     *
     * @param key the metadata key
     * @param <T> the expected type
     * @return Optional containing the value, or empty if not found
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getMetadata(final String key) {
        return Optional.ofNullable((T) metadata.get(key));
    }
}

