package dev.dmgiangi.core.server.infrastructure.messaging.outbound.trasformer;

import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.core.GenericTransformer;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class CommandPayloadTransformer implements GenericTransformer<DeviceCommandEvent, List<Message<String>>> {

    private final ObjectMapper objectMapper;

    @Override
    public List<Message<String>> transform(DeviceCommandEvent event) {
        return switch (event.type()) {
            case RELAY -> handleRelay(event);
            case FAN -> handleFan(event);
            default -> throw new IllegalArgumentException("Unsupported device type: " + event.type());
        };
    }

    private List<Message<String>> handleRelay(DeviceCommandEvent event) {
        // Implementation for standard RELAY (single message)
        final var state = Boolean.TRUE.equals(event.value());
        final var payload = objectMapper.writeValueAsString(new RelayPayload(state ? "1" : "0"));

        final var controllerId = event.deviceId().controllerId();
        final var componentId = event.deviceId().componentId();

        // Topic: /{controllerId}/digital_output/{componentId}/set
        // AMQP: .controllerId.digital_output.componentId.set
        final var routingKey = ".%s.digital_output.%s.set".formatted(controllerId, componentId);

        return Collections.singletonList(createMessage(payload, routingKey));
    }

    /**
     * Handles FAN device commands for 3-relay discrete fan control.
     * <p>
     * Sends a single message to the fan topic with the speed value (0-4).
     * The firmware's FanHandler uses 3 relays to achieve 5 discrete speed states:
     * - 0 = OFF (all relays disabled)
     * - 1-4 = Increasing speed levels
     * </p>
     *
     * @param event the device command event containing fan speed (0-4)
     * @return list containing a single MQTT message to the fan topic
     */
    private List<Message<String>> handleFan(DeviceCommandEvent event) {
        final var speed = (Integer) event.value();
        final var controllerId = event.deviceId().controllerId();
        final var componentId = event.deviceId().componentId();

        // Topic: /{controllerId}/fan/{componentId}/set
        // AMQP: .controllerId.fan.componentId.set
        final var routingKey = ".%s.fan.%s.set".formatted(controllerId, componentId);
        final var payload = String.valueOf(speed);

        return Collections.singletonList(createMessage(payload, routingKey));
    }

    private Message<String> createMessage(String payload, String routingKey) {
        return MessageBuilder
                .withPayload(payload)
                .setHeader("mqtt_routing_key", routingKey)
                .build();
    }

    // DTOs
    record RelayPayload(String state) {
    }
}