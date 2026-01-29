package dev.dmgiangi.core.server.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.model.FanValue;
import dev.dmgiangi.core.server.domain.model.RelayValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;

/**
 * Request DTO for applying an override.
 * Per Phase 5.4/5.6: Override request with type, value, reason, and optional TTL.
 *
 * @param type       the device type (RELAY or FAN)
 * @param value      the override value (Boolean for RELAY, Integer 0-4 for FAN)
 * @param reason     the reason for the override
 * @param ttlSeconds optional time-to-live in seconds (null = permanent)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request to apply an override to a device or system")
public record OverrideRequestDto(
        @Schema(description = "Device type", example = "RELAY", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Device type is required")
        DeviceType type,

        @Schema(description = "Override value. Boolean for RELAY, Integer 0-4 for FAN",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Value is required")
        Object value,

        @Schema(description = "Reason for the override", example = "Scheduled maintenance",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Reason is required")
        String reason,

        @Schema(description = "Time-to-live in seconds. Null means permanent override",
                example = "3600", nullable = true)
        Long ttlSeconds
) {
    /**
     * Converts the raw value to a typed DeviceValue.
     *
     * @return the typed DeviceValue
     * @throws IllegalArgumentException if the value is invalid for the type
     */
    public DeviceValue toDeviceValue() {
        return switch (type) {
            case RELAY -> {
                final var booleanValue = switch (value) {
                    case Boolean b -> b;
                    case String s -> Boolean.parseBoolean(s);
                    case Number n -> n.intValue() != 0;
                    default -> throw new IllegalArgumentException(
                            "Invalid value type for RELAY: " + value.getClass().getSimpleName()
                    );
                };
                yield new RelayValue(booleanValue);
            }
            case FAN -> {
                final var intValue = switch (value) {
                    case Number n -> n.intValue();
                    case String s -> Integer.parseInt(s);
                    default -> throw new IllegalArgumentException(
                            "Invalid value type for FAN: " + value.getClass().getSimpleName()
                    );
                };
                if (intValue < 0 || intValue > 4) {
                    throw new IllegalArgumentException(
                            "FAN value must be between 0 and 4, got: " + intValue
                    );
                }
                yield new FanValue(intValue);
            }
            case TEMPERATURE_SENSOR -> throw new IllegalArgumentException(
                    "Cannot set override for input device type: TEMPERATURE_SENSOR"
            );
        };
    }

    /**
     * Returns the TTL as a Duration, or null if permanent.
     *
     * @return the TTL duration or null
     */
    public Duration getTtl() {
        return ttlSeconds != null ? Duration.ofSeconds(ttlSeconds) : null;
    }
}

