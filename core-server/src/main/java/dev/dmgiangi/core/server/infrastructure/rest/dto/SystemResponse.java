package dev.dmgiangi.core.server.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Response DTO for FunctionalSystem.
 * Per Phase 5.2: System with aggregated device state.
 *
 * @param id               the system ID
 * @param type             the system type (TERMOCAMINO, HVAC, etc.)
 * @param name             the system name
 * @param configuration    the system configuration
 * @param deviceIds        the device IDs belonging to this system
 * @param deviceStates     aggregated device states (optional, loaded on demand)
 * @param failSafeDefaults the fail-safe defaults
 * @param activeOverrides  list of active overrides on this system
 * @param createdAt        when the system was created
 * @param updatedAt        when the system was last updated
 * @param createdBy        who created the system
 * @param version          optimistic locking version
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemResponse(
        String id,
        String type,
        String name,
        Map<String, Object> configuration,
        Set<String> deviceIds,
        List<DeviceStateInfo> deviceStates,
        Map<String, Object> failSafeDefaults,
        List<OverrideInfo> activeOverrides,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        Long version
) {
    /**
     * Creates a response from FunctionalSystemData without device states.
     *
     * @param data the system data
     * @return the response DTO
     */
    public static SystemResponse fromData(final FunctionalSystemData data) {
        return new SystemResponse(
                data.id(),
                data.type(),
                data.name(),
                data.configuration(),
                data.deviceIds(),
                null,
                data.failSafeDefaults(),
                null,
                data.createdAt(),
                data.updatedAt(),
                data.createdBy(),
                data.version()
        );
    }

    /**
     * Creates a response with device states and overrides.
     *
     * @param data            the system data
     * @param deviceStates    the device states
     * @param activeOverrides the active overrides
     * @return the response DTO
     */
    public static SystemResponse withDetails(
            final FunctionalSystemData data,
            final List<DeviceStateInfo> deviceStates,
            final List<OverrideInfo> activeOverrides
    ) {
        return new SystemResponse(
                data.id(),
                data.type(),
                data.name(),
                data.configuration(),
                data.deviceIds(),
                deviceStates,
                data.failSafeDefaults(),
                activeOverrides,
                data.createdAt(),
                data.updatedAt(),
                data.createdBy(),
                data.version()
        );
    }

    /**
     * Device state information for aggregated view.
     */
    public record DeviceStateInfo(
            String deviceId,
            String type,
            Object currentValue,
            Object desiredValue,
            Object reportedValue,
            boolean isConverged,
            Instant lastActivity
    ) {
    }

    /**
     * Override information for system view.
     */
    public record OverrideInfo(
            String targetId,
            String scope,
            String category,
            Object value,
            String reason,
            Instant expiresAt,
            String createdBy
    ) {
    }
}

