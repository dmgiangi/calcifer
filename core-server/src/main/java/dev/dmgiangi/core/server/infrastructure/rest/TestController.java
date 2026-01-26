package dev.dmgiangi.core.server.infrastructure.rest;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;
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

        // Convert String/Number to Integer for FAN type
        if (request.type() == DeviceType.FAN) {
            if (value instanceof String s) {
                try {
                    value = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid FAN speed value: " + s);
                }
            } else if (value instanceof Number n) {
                value = n.intValue();
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
