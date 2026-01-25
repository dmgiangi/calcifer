package dev.dmgiangi.core.server.infrastructure.rest;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.StepRelayState;
import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {
    private final ApplicationEventPublisher eventPublisher;

    @PostMapping("/command")
    public void sendCommand(@RequestBody CommandRequest request) {
        Object value = request.value();

        if (request.type() == DeviceType.STEP_RELAY && value instanceof String s) {
            try {
                value = StepRelayState.valueOf(s);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid StepRelayState: " + s);
            }
        }

        final var event = new DeviceCommandEvent(
                new DeviceId(request.controllerId(), request.componentId()),
                request.type(),
                value
        );
        eventPublisher.publishEvent(event);
    }

    public record CommandRequest(
            String controllerId,
            String componentId,
            DeviceType type,
            Object value
    ) {
    }
}
