package dev.dmgiangi.core.server.infrastructure.rest;

import dev.dmgiangi.core.server.application.override.OverrideApplicationService;
import dev.dmgiangi.core.server.domain.exception.ResourceNotFoundException;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import dev.dmgiangi.core.server.domain.port.OverrideRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.infrastructure.rest.dto.OverrideRequestDto;
import dev.dmgiangi.core.server.infrastructure.rest.dto.OverrideResponse;
import dev.dmgiangi.core.server.infrastructure.rest.dto.SystemConfigurationRequest;
import dev.dmgiangi.core.server.infrastructure.rest.dto.SystemResponse;
import dev.dmgiangi.core.server.infrastructure.rest.dto.SystemResponse.DeviceStateInfo;
import dev.dmgiangi.core.server.infrastructure.rest.dto.SystemResponse.OverrideInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for FunctionalSystem management.
 * Per Phase 5.1: Base /api/v1/systems with error handling.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/systems")
@RequiredArgsConstructor
@Validated
@Tag(name = "FunctionalSystem", description = "Operations for managing FunctionalSystem aggregates")
public class SystemController {

    private static final String ANONYMOUS_USER = "anonymous";

    private final FunctionalSystemRepository systemRepository;
    private final OverrideApplicationService overrideService;
    private final OverrideRepository overrideRepository;
    private final DeviceStateRepository deviceStateRepository;

    // ========== System CRUD Operations ==========

    /**
     * Lists all FunctionalSystems.
     * Per Phase 5.1: GET /api/v1/systems
     *
     * @return list of all systems
     */
    @Operation(
            summary = "List all FunctionalSystems",
            description = "Retrieves a list of all configured FunctionalSystems (e.g., TERMOCAMINO, HVAC, IRRIGATION)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of systems retrieved successfully")
    })
    @GetMapping
    public ResponseEntity<List<SystemResponse>> listSystems() {
        log.debug("Listing all systems");
        final var systems = systemRepository.findAll().stream()
                .map(SystemResponse::fromData)
                .toList();
        return ResponseEntity.ok(systems);
    }

    /**
     * Gets a FunctionalSystem by ID with aggregated device state.
     * Per Phase 5.2: GET /api/v1/systems/{id}
     *
     * @param id the system ID
     * @return the system with device states
     */
    @Operation(
            summary = "Get FunctionalSystem by ID",
            description = "Retrieves a FunctionalSystem with its configuration, device states, and active overrides."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "System retrieved successfully",
                    content = @Content(schema = @Schema(implementation = SystemResponse.class))),
            @ApiResponse(responseCode = "404", description = "System not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<SystemResponse> getSystem(
            @Parameter(description = "System identifier", example = "termocamino-living")
            @PathVariable @NotBlank final String id
    ) {
        log.debug("Getting system: {}", id);
        return systemRepository.findById(id)
                .map(this::buildSystemResponseWithDetails)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> ResourceNotFoundException.system(id));
    }

    /**
     * Updates system configuration.
     * Per Phase 5.3: PATCH /api/v1/systems/{id}/configuration
     *
     * @param id      the system ID
     * @param request the configuration update request
     * @return the updated system
     */
    @Operation(
            summary = "Update system configuration",
            description = "Partially updates the configuration of a FunctionalSystem (mode, target temperature, schedules, etc.)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Configuration updated successfully",
                    content = @Content(schema = @Schema(implementation = SystemResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or no updates provided"),
            @ApiResponse(responseCode = "404", description = "System not found")
    })
    @PatchMapping("/{id}/configuration")
    public ResponseEntity<SystemResponse> updateConfiguration(
            @Parameter(description = "System identifier", example = "termocamino-living")
            @PathVariable @NotBlank final String id,
            @RequestBody @Valid final SystemConfigurationRequest request
    ) {
        log.info("Updating configuration for system: {}", id);

        if (!request.hasUpdates()) {
            return ResponseEntity.badRequest().build();
        }

        final var existing = systemRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.system(id));

        final var updatedConfig = mergeConfiguration(existing.configuration(), request);
        final var updated = new FunctionalSystemData(
                existing.id(),
                existing.type(),
                existing.name(),
                updatedConfig,
                existing.deviceIds(),
                existing.failSafeDefaults(),
                existing.createdAt(),
                java.time.Instant.now(),
                existing.createdBy(),
                existing.version()
        );

        final var saved = systemRepository.save(updated);
        log.info("Configuration updated for system: {}", id);

        return ResponseEntity.ok(buildSystemResponseWithDetails(saved));
    }

    // ========== System Override Operations ==========

    /**
     * Applies an override to a system.
     * Per Phase 5.4: PUT /api/v1/systems/{id}/override/{category}
     *
     * @param id        the system ID
     * @param category  the override category
     * @param request   the override request
     * @param principal the authenticated user
     * @return the override response
     */
    @Operation(
            summary = "Apply a system override",
            description = "Applies an override to all devices in a FunctionalSystem. System overrides affect " +
                    "all devices in the system but have lower precedence than device-level overrides."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override applied successfully",
                    content = @Content(schema = @Schema(implementation = OverrideResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "404", description = "System not found"),
            @ApiResponse(responseCode = "422", description = "Override blocked by safety rules")
    })
    @Tag(name = "System Override")
    @PutMapping("/{id}/override/{category}")
    public ResponseEntity<OverrideResponse> applySystemOverride(
            @Parameter(description = "System identifier", example = "termocamino-living")
            @PathVariable @NotBlank final String id,
            @Parameter(description = "Override category", example = "MAINTENANCE")
            @PathVariable final OverrideCategory category,
            @RequestBody @Valid final OverrideRequestDto request,
            final Principal principal
    ) {
        log.info("Applying system override for {} category {}", id, category);

        // Verify system exists
        if (!systemRepository.existsById(id)) {
            throw ResourceNotFoundException.system(id);
        }

        final var createdBy = principal != null ? principal.getName() : ANONYMOUS_USER;
        final var result = overrideService.applySystemOverride(
                id,
                category,
                request.toDeviceValue(),
                request.reason(),
                request.getTtl(),
                createdBy
        );

        final var response = OverrideResponse.fromValidationResult(result);
        final var status = result.isBlocked() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK;

        return ResponseEntity.status(status).body(response);
    }

    /**
     * Cancels a system override.
     * Per Phase 5.5: DELETE /api/v1/systems/{id}/override/{category}
     *
     * @param id       the system ID
     * @param category the override category to cancel
     * @return the override response
     */
    @Operation(
            summary = "Cancel a system override",
            description = "Cancels an active override for a specific system and category."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override cancelled or not found",
                    content = @Content(schema = @Schema(implementation = OverrideResponse.class))),
            @ApiResponse(responseCode = "404", description = "System not found")
    })
    @Tag(name = "System Override")
    @DeleteMapping("/{id}/override/{category}")
    public ResponseEntity<OverrideResponse> cancelSystemOverride(
            @Parameter(description = "System identifier", example = "termocamino-living")
            @PathVariable @NotBlank final String id,
            @Parameter(description = "Override category to cancel", example = "MAINTENANCE")
            @PathVariable final OverrideCategory category
    ) {
        log.info("Cancelling system override for {} category {}", id, category);

        // Verify system exists
        if (!systemRepository.existsById(id)) {
            throw ResourceNotFoundException.system(id);
        }

        final var cancelled = overrideService.cancelSystemOverride(id, category);

        if (cancelled) {
            return ResponseEntity.ok(OverrideResponse.cancelled(id, OverrideScope.SYSTEM, category));
        } else {
            return ResponseEntity.ok(OverrideResponse.notFound(id, OverrideScope.SYSTEM, category));
        }
    }

    /**
     * Lists all active overrides for a system.
     *
     * @param id the system ID
     * @return list of active overrides
     */
    @Operation(
            summary = "List system overrides",
            description = "Lists all active overrides for a specific FunctionalSystem."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of active overrides"),
            @ApiResponse(responseCode = "404", description = "System not found")
    })
    @Tag(name = "System Override")
    @GetMapping("/{id}/overrides")
    public ResponseEntity<List<OverrideInfo>> listSystemOverrides(
            @PathVariable @NotBlank final String id
    ) {
        log.debug("Listing overrides for system: {}", id);

        if (!systemRepository.existsById(id)) {
            throw ResourceNotFoundException.system(id);
        }

        final var overrides = overrideService.listSystemOverrides(id).stream()
                .map(this::toOverrideInfo)
                .toList();

        return ResponseEntity.ok(overrides);
    }

    // ========== Private Helper Methods ==========

    /**
     * Builds a SystemResponse with device states and overrides.
     */
    private SystemResponse buildSystemResponseWithDetails(final FunctionalSystemData data) {
        final var deviceStates = data.deviceIds().stream()
                .map(this::loadDeviceStateInfo)
                .toList();

        final var overrides = overrideRepository.findActiveByTarget(data.id()).stream()
                .map(this::toOverrideInfo)
                .toList();

        return SystemResponse.withDetails(data, deviceStates, overrides);
    }

    /**
     * Loads device state info for a device ID.
     */
    private DeviceStateInfo loadDeviceStateInfo(final String deviceIdStr) {
        final var parts = deviceIdStr.split(":");
        if (parts.length != 2) {
            return new DeviceStateInfo(deviceIdStr, "UNKNOWN", null, null, null, false, null);
        }

        final var deviceId = new dev.dmgiangi.core.server.domain.model.DeviceId(parts[0], parts[1]);
        final var snapshot = deviceStateRepository.findTwinSnapshot(deviceId);

        return snapshot.map(s -> new DeviceStateInfo(
                deviceIdStr,
                s.intent() != null ? s.intent().type().name() : "UNKNOWN",
                extractRawValue(s.intent() != null ? s.intent().value() : null),
                extractRawValue(s.desired() != null ? s.desired().value() : null),
                extractRawValue(s.reported() != null ? s.reported().value() : null),
                s.isConverged(),
                s.reported() != null ? s.reported().reportedAt() : null
        )).orElse(new DeviceStateInfo(deviceIdStr, "UNKNOWN", null, null, null, false, null));
    }

    /**
     * Converts OverrideData to OverrideInfo.
     */
    private OverrideInfo toOverrideInfo(final OverrideRepository.OverrideData data) {
        return new OverrideInfo(
                data.targetId(),
                data.scope().name(),
                data.category().name(),
                extractRawValueFromObject(data.value()),
                data.reason(),
                data.expiresAt(),
                data.createdBy()
        );
    }

    /**
     * Merges existing configuration with update request.
     */
    private Map<String, Object> mergeConfiguration(
            final Map<String, Object> existing,
            final SystemConfigurationRequest request
    ) {
        final var merged = new HashMap<>(existing != null ? existing : Map.of());

        if (request.mode() != null) {
            merged.put("mode", request.mode());
        }
        if (request.targetTemperature() != null) {
            merged.put("targetTemperature", request.targetTemperature());
        }
        if (request.scheduleStart() != null) {
            merged.put("scheduleStart", request.scheduleStart().toString());
        }
        if (request.scheduleEnd() != null) {
            merged.put("scheduleEnd", request.scheduleEnd().toString());
        }
        if (request.safetyThresholds() != null) {
            merged.put("safetyThresholds", mergeSafetyThresholds(
                    (Map<String, Object>) merged.get("safetyThresholds"),
                    request.safetyThresholds()
            ));
        }
        if (request.metadata() != null) {
            merged.putAll(request.metadata());
        }

        return merged;
    }

    /**
     * Merges safety thresholds.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeSafetyThresholds(
            final Map<String, Object> existing,
            final SystemConfigurationRequest.SafetyThresholdsDto dto
    ) {
        final var merged = new HashMap<>(existing != null ? existing : Map.of());

        if (dto.maxTemperature() != null) merged.put("maxTemperature", dto.maxTemperature());
        if (dto.minTemperature() != null) merged.put("minTemperature", dto.minTemperature());
        if (dto.criticalTemperature() != null) merged.put("criticalTemperature", dto.criticalTemperature());
        if (dto.maxFanSpeed() != null) merged.put("maxFanSpeed", dto.maxFanSpeed());
        if (dto.hysteresis() != null) merged.put("hysteresis", dto.hysteresis());

        return merged;
    }

    /**
     * Extracts raw value from DeviceValue for JSON serialization.
     */
    private Object extractRawValue(final DeviceValue value) {
        if (value == null) return null;
        return switch (value) {
            case dev.dmgiangi.core.server.domain.model.RelayValue r -> r.state();
            case dev.dmgiangi.core.server.domain.model.FanValue f -> f.speed();
        };
    }

    /**
     * Extracts raw value from Object (which may be DeviceValue or primitive).
     */
    private Object extractRawValueFromObject(final Object value) {
        if (value == null) return null;
        if (value instanceof DeviceValue dv) {
            return extractRawValue(dv);
        }
        return value;
    }
}

