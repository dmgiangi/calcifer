package dev.dmgiangi.core.server.infrastructure.messaging.inbound.trasformer;

import dev.dmgiangi.core.server.domain.model.ActuatorFeedback;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.core.GenericTransformer;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Transforms AMQP messages from actuator state topics into ActuatorFeedback objects.
 * Parses the routing key to extract device identification and type.
 *
 * <p>Routing key format: {@code .<clientId>.<handlerType>.<name>.state}
 * <ul>
 *   <li>FAN: {@code *.*.fan.*.state} → DeviceType.FAN</li>
 *   <li>RELAY: {@code *.*.digital_output.*.state} → DeviceType.RELAY</li>
 * </ul>
 */
@Slf4j
@Component
public class AmqpToActuatorFeedbackTransformer implements GenericTransformer<Message<?>, ActuatorFeedback> {

    @Override
    public ActuatorFeedback transform(Message<?> source) {
        final var payload = (byte[]) source.getPayload();
        final var rawValue = new String(payload);
        final var routingInfo = getRoutingInfo(source);
        final var receivedAt = Instant.now();

        log.debug("Received actuator feedback: deviceId={}, type={}, rawValue={}",
            routingInfo.deviceId(), routingInfo.deviceType(), rawValue);

        return new ActuatorFeedback(
            routingInfo.deviceId(),
            routingInfo.deviceType(),
            rawValue,
            receivedAt
        );
    }

    private static RoutingInfo getRoutingInfo(Message<?> source) {
        final var routingKey = source.getHeaders().get("amqp_receivedRoutingKey", String.class);

        if (routingKey == null) {
            throw new IllegalArgumentException("Missing amqp_receivedRoutingKey header");
        }

        final var parts = routingKey.split("\\.");

        // Mapping parts (index 0 is empty because of the leading dot)
        // Format: .<clientId>.<handlerType>.<name>.state
        final var clientId = parts[1];
        final var handlerType = parts[2];
        final var componentName = parts[3];

        final var deviceType = mapHandlerTypeToDeviceType(handlerType);
        final var deviceId = new DeviceId(clientId, componentName);

        return new RoutingInfo(deviceId, deviceType);
    }

    private static DeviceType mapHandlerTypeToDeviceType(String handlerType) {
        return switch (handlerType) {
            case "fan" -> DeviceType.FAN;
            case "digital_output" -> DeviceType.RELAY;
            default -> throw new IllegalArgumentException("Unknown handler type: " + handlerType);
        };
    }

    private record RoutingInfo(
        DeviceId deviceId,
        DeviceType deviceType
    ) {}
}

