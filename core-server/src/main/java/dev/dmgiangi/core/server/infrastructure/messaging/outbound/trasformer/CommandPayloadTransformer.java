package dev.dmgiangi.core.server.infrastructure.messaging.outbound.trasformer;

import dev.dmgiangi.core.server.domain.model.StepRelayState;
import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.core.GenericTransformer;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
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
            case STEP_RELAY -> handleStepRelay(event);
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

    private List<Message<String>> handleStepRelay(DeviceCommandEvent event) {
        final var messages = new ArrayList<Message<String>>();

        final var state = (StepRelayState) event.value();
        final var controllerId = event.deviceId().controllerId();
        final var componentId = event.deviceId().componentId();

        // 1. Messaggio A: Digital Output (Enable/Disable)
        // Topic: /{controllerId}/digital_output/{componentId}/set
        final var digitalRoutingKey = ".%s.digital_output.%s.set".formatted(controllerId, componentId);
        final var relayMessage = createMessage(state.getDigitalValue(), digitalRoutingKey);
        messages.add(relayMessage);

        // 2. Messaggio B: PWM Output (Power Level)
        // Topic: /{controllerId}/pwm/{componentId}/set
        final var pwmRoutingKey = ".%s.pwm.%s.set".formatted(controllerId, componentId);
        final var powerMessage = createMessage(state.getPwmValue(), pwmRoutingKey);
        messages.add(powerMessage);

        return messages;
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