package dev.dmgiangi.core.server.infrastructure.rest;

import dev.dmgiangi.core.server.application.override.OverrideApplicationService;
import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.domain.service.DeviceSystemMappingService;
import dev.dmgiangi.core.server.infrastructure.rest.dto.IntentRequest;
import dev.dmgiangi.core.server.infrastructure.rest.dto.IntentResponse;
import dev.dmgiangi.core.server.infrastructure.rest.dto.OverrideRequestDto;
import dev.dmgiangi.core.server.infrastructure.rest.dto.OverrideResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * REST controller for managing device user intents, overrides, and retrieving device twin snapshots.
 * Per Phase 0.12: Validated with @Validated on class, @Valid on @RequestBody.
 * Per Phase 5.6/5.7: Device override endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
@Validated
@Tag(name = "Device Intent", description = "Operations for managing device user intents and twin snapshots")
public class DeviceIntentController {

    private static final String DEVICE_ID_PATTERN = "^[a-zA-Z0-9_-]+$";
    private static final String DEVICE_ID_MESSAGE = "must contain only alphanumeric characters, underscores, and hyphens";
    private static final String ANONYMOUS_USER = "anonymous";

    private final DeviceStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final OverrideApplicationService overrideService;
    private final DeviceSystemMappingService deviceSystemMappingService;

    /**
     * Submits a user intent for a specific device.
     *
     * <p>Per Phase 5.8: If the device belongs to a FunctionalSystem, the intent will be
     * processed through the LogicEngine with safety rules. The response indicates whether
     * the device is standalone or part of a system.
     *
     * @param controllerId the controller identifier
     * @param componentId  the component identifier
     * @param request      the intent request containing type and value
     * @return 200 OK with intent response containing system context
     */
    @Operation(
            summary = "Submit a user intent for a device",
            description = "Sets the desired state for a device. If the device belongs to a FunctionalSystem, " +
                    "the intent will be processed through safety rules before calculating the desired state."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Intent submitted successfully",
                    content = @Content(schema = @Schema(implementation = IntentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request (validation error)"),
            @ApiResponse(responseCode = "422", description = "Intent rejected by safety rules")
    })
    @PostMapping("/{controllerId}/{componentId}/intent")
    public ResponseEntity<IntentResponse> submitIntent(
            @PathVariable @NotBlank(message = "Controller ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String controllerId,
            @PathVariable @NotBlank(message = "Component ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String componentId,
            @RequestBody @Valid final IntentRequest request
    ) {
        final var deviceId = new DeviceId(controllerId, componentId);
        final var deviceValue = convertToDeviceValue(request.type(), request.value());
        final var intent = UserIntent.now(deviceId, request.type(), deviceValue);

        // Save intent and publish event (triggers async reconciliation via LogicEngine)
        repository.saveUserIntent(intent);
        eventPublisher.publishEvent(new UserIntentChangedEvent(this, intent));

        // Check if device belongs to a FunctionalSystem for informative response
        final var systemOpt = deviceSystemMappingService.findSystemByDevice(deviceId);
        final var response = systemOpt
                .map(system -> IntentResponse.inSystem(intent, system))
                .orElseGet(() -> IntentResponse.standalone(intent));

        log.debug("Intent submitted for device {}: system={}", deviceId,
                systemOpt.map(s -> s.id()).orElse("standalone"));

        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the complete device twin snapshot for a specific device.
     *
     * @param controllerId the controller identifier
     * @param componentId  the component identifier
     * @return the device twin snapshot, or 404 if not found
     */
    @Operation(
            summary = "Get device twin snapshot",
            description = "Retrieves the complete three-state digital twin for a device: " +
                    "User Intent, Reported State, and Desired State."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Twin snapshot retrieved successfully",
                    content = @Content(schema = @Schema(implementation = DeviceTwinSnapshot.class))),
            @ApiResponse(responseCode = "404", description = "Device not found")
    })
    @GetMapping("/{controllerId}/{componentId}/twin")
    public ResponseEntity<DeviceTwinSnapshot> getTwinSnapshot(
            @Parameter(description = "Controller identifier (e.g., esp32-kitchen)", example = "esp32-kitchen")
            @PathVariable @NotBlank(message = "Controller ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String controllerId,
            @Parameter(description = "Component identifier (e.g., main-light)", example = "main-light")
            @PathVariable @NotBlank(message = "Component ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String componentId
    ) {
        final var deviceId = new DeviceId(controllerId, componentId);
        return repository.findTwinSnapshot(deviceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Converts the raw request value to the appropriate DeviceValue based on device type.
     *
     * @param type  the device type
     * @param value the raw value from the request
     * @return the typed DeviceValue
     */
    private DeviceValue convertToDeviceValue(DeviceType type, Object value) {
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
                yield new FanValue(intValue);
            }
            case TEMPERATURE_SENSOR -> throw new IllegalArgumentException(
                    "Cannot set intent for input device type: TEMPERATURE_SENSOR"
            );
        };
    }

    // ========== Device Override Operations (Phase 5.6/5.7) ==========

    /**
     * Applies an override to a device.
     * Per Phase 5.6: PUT /api/devices/{controllerId}/{componentId}/override/{category}
     *
     * @param controllerId the controller identifier
     * @param componentId  the component identifier
     * @param category     the override category
     * @param request      the override request
     * @param principal    the authenticated user
     * @return the override response
     */
    @Operation(
            summary = "Apply a device override",
            description = "Applies an override to a specific device. Overrides take precedence over user intents " +
                    "based on category: EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override applied successfully",
                    content = @Content(schema = @Schema(implementation = OverrideResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "422", description = "Override blocked by safety rules")
    })
    @Tag(name = "Device Override")
    @PutMapping("/{controllerId}/{componentId}/override/{category}")
    public ResponseEntity<OverrideResponse> applyDeviceOverride(
            @PathVariable @NotBlank(message = "Controller ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String controllerId,
            @PathVariable @NotBlank(message = "Component ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String componentId,
            @PathVariable final OverrideCategory category,
            @RequestBody @Valid final OverrideRequestDto request,
            final Principal principal
    ) {
        final var deviceIdStr = controllerId + ":" + componentId;
        log.info("Applying device override for {} category {}", deviceIdStr, category);

        final var createdBy = principal != null ? principal.getName() : ANONYMOUS_USER;
        final var result = overrideService.applyDeviceOverride(
                deviceIdStr,
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
     * Cancels a device override.
     * Per Phase 5.7: DELETE /api/devices/{controllerId}/{componentId}/override/{category}
     *
     * @param controllerId the controller identifier
     * @param componentId  the component identifier
     * @param category     the override category to cancel
     * @return the override response
     */
    @Operation(
            summary = "Cancel a device override",
            description = "Cancels an active override for a specific device and category."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override cancelled or not found",
                    content = @Content(schema = @Schema(implementation = OverrideResponse.class)))
    })
    @Tag(name = "Device Override")
    @DeleteMapping("/{controllerId}/{componentId}/override/{category}")
    public ResponseEntity<OverrideResponse> cancelDeviceOverride(
            @Parameter(description = "Controller identifier", example = "esp32-kitchen")
            @PathVariable @NotBlank(message = "Controller ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String controllerId,
            @Parameter(description = "Component identifier", example = "main-light")
            @PathVariable @NotBlank(message = "Component ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String componentId,
            @Parameter(description = "Override category to cancel", example = "MANUAL")
            @PathVariable final OverrideCategory category
    ) {
        final var deviceIdStr = controllerId + ":" + componentId;
        log.info("Cancelling device override for {} category {}", deviceIdStr, category);

        final var cancelled = overrideService.cancelDeviceOverride(deviceIdStr, category);

        if (cancelled) {
            return ResponseEntity.ok(OverrideResponse.cancelled(deviceIdStr, OverrideScope.DEVICE, category));
        } else {
            return ResponseEntity.ok(OverrideResponse.notFound(deviceIdStr, OverrideScope.DEVICE, category));
        }
    }

    /**
     * Lists all active overrides for a device.
     *
     * @param controllerId the controller identifier
     * @param componentId  the component identifier
     * @return list of active overrides
     */
    @Operation(
            summary = "List device overrides",
            description = "Lists all active overrides for a specific device."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of active overrides")
    })
    @Tag(name = "Device Override")
    @GetMapping("/{controllerId}/{componentId}/overrides")
    public ResponseEntity<?> listDeviceOverrides(
            @Parameter(description = "Controller identifier", example = "esp32-kitchen")
            @PathVariable @NotBlank(message = "Controller ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String controllerId,
            @Parameter(description = "Component identifier", example = "main-light")
            @PathVariable @NotBlank(message = "Component ID is required")
            @Pattern(regexp = DEVICE_ID_PATTERN, message = DEVICE_ID_MESSAGE) final String componentId
    ) {
        final var deviceIdStr = controllerId + ":" + componentId;
        log.debug("Listing overrides for device: {}", deviceIdStr);

        final var overrides = overrideService.listDeviceOverrides(deviceIdStr);
        return ResponseEntity.ok(overrides);
    }
}

