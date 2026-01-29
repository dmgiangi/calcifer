package dev.dmgiangi.core.server.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalTime;
import java.util.Map;

/**
 * Request DTO for updating system configuration.
 * Per Phase 5.3: Partial update of configuration (mode, targetTemp, schedule).
 *
 * <p>All fields are optional - only provided fields will be updated.
 *
 * @param mode              the operational mode (OFF, MANUAL, AUTO, ECO, BOOST, AWAY)
 * @param targetTemperature the target temperature in Celsius
 * @param scheduleStart     the schedule start time
 * @param scheduleEnd       the schedule end time
 * @param safetyThresholds  safety threshold overrides
 * @param metadata          additional configuration metadata
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemConfigurationRequest(
        String mode,

        @Min(value = 5, message = "Target temperature must be at least 5°C")
        @Max(value = 35, message = "Target temperature must be at most 35°C")
        Double targetTemperature,

        LocalTime scheduleStart,

        LocalTime scheduleEnd,

        SafetyThresholdsDto safetyThresholds,

        Map<String, Object> metadata
) {
    /**
     * Checks if any field is set.
     *
     * @return true if at least one field is set
     */
    public boolean hasUpdates() {
        return mode != null ||
                targetTemperature != null ||
                scheduleStart != null ||
                scheduleEnd != null ||
                safetyThresholds != null ||
                (metadata != null && !metadata.isEmpty());
    }

    /**
     * Safety thresholds DTO for partial updates.
     */
    public record SafetyThresholdsDto(
            @Min(value = 0, message = "Max temperature must be at least 0°C")
            @Max(value = 100, message = "Max temperature must be at most 100°C")
            Double maxTemperature,

            @Min(value = -20, message = "Min temperature must be at least -20°C")
            @Max(value = 50, message = "Min temperature must be at most 50°C")
            Double minTemperature,

            @Min(value = 50, message = "Critical temperature must be at least 50°C")
            @Max(value = 150, message = "Critical temperature must be at most 150°C")
            Double criticalTemperature,

            @Min(value = 0, message = "Max fan speed must be at least 0")
            @Max(value = 4, message = "Max fan speed must be at most 4")
            Integer maxFanSpeed,

            @Min(value = 0, message = "Hysteresis must be at least 0°C")
            @Max(value = 10, message = "Hysteresis must be at most 10°C")
            Double hysteresis
    ) {
    }
}

