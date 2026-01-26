package dev.dmgiangi.core.server.infrastructure.rest;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.model.FanValue;
import dev.dmgiangi.core.server.domain.model.RelayValue;
import dev.dmgiangi.core.server.domain.model.UserIntent;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.infrastructure.rest.dto.IntentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing device user intents and retrieving device twin snapshots.
 */
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceIntentController {

    private final DeviceStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Submits a user intent for a specific device.
     *
     * @param controllerId the controller identifier
     * @param componentId  the component identifier
     * @param request      the intent request containing type and value
     * @return 200 OK on success
     */
    @PostMapping("/{controllerId}/{componentId}/intent")
    public ResponseEntity<Void> submitIntent(
            @PathVariable String controllerId,
            @PathVariable String componentId,
            @RequestBody IntentRequest request
    ) {
        final var deviceId = new DeviceId(controllerId, componentId);
        final var deviceValue = convertToDeviceValue(request.type(), request.value());
        final var intent = UserIntent.now(deviceId, request.type(), deviceValue);

        repository.saveUserIntent(intent);
        eventPublisher.publishEvent(new UserIntentChangedEvent(this, intent));

        return ResponseEntity.ok().build();
    }

    /**
     * Retrieves the complete device twin snapshot for a specific device.
     *
     * @param controllerId the controller identifier
     * @param componentId  the component identifier
     * @return the device twin snapshot, or 404 if not found
     */
    @GetMapping("/{controllerId}/{componentId}/twin")
    public ResponseEntity<DeviceTwinSnapshot> getTwinSnapshot(
            @PathVariable String controllerId,
            @PathVariable String componentId
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
}

