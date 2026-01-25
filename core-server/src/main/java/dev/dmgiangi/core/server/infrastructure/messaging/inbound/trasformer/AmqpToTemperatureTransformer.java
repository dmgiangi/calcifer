package dev.dmgiangi.core.server.infrastructure.messaging.inbound.trasformer;

import dev.dmgiangi.core.server.domain.temperature.SensorType;
import dev.dmgiangi.core.server.domain.temperature.Temperature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.core.GenericTransformer;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class AmqpToTemperatureTransformer implements GenericTransformer<Message<?>, Temperature> {

    @Override
    public Temperature transform(Message<?> source) {
        final var payload = (byte[]) source.getPayload();
        final var sensorValue = getSensorValue(payload);
        final var routingInfo = getRoutingInfo(source);

        return new Temperature(
            routingInfo.client(),
            routingInfo.type(),
            routingInfo.sensorName(),
            sensorValue.isError(),
            sensorValue.value()
        );
    }

    private static AmqpToTemperatureTransformer.RoutingInfo getRoutingInfo(Message<?> source) {
        final var routingKey = source.getHeaders().get("amqp_receivedRoutingKey", String.class);

        if (routingKey == null) {
            throw new IllegalArgumentException("Missing amqp_receivedRoutingKey header");
        }

        final var parts = routingKey.split("\\.");

        // Mapping parts (index 0 is empty because of the leading dot)
        final var client = parts[1];
        final var type = SensorType.fromString(parts[2]);
        final var sensorName = parts[3];

        return new RoutingInfo(client, type, sensorName);
    }

    private record RoutingInfo(
        String client,
        SensorType type,
        String sensorName
    ) {}

    private static SensorValue getSensorValue(byte[] payload) {
        try {
            final var string = new String(payload);
            final var value = Double.parseDouble(string);
            return new SensorValue(false, value);
        } catch (NumberFormatException e) {
            return new SensorValue(true, Double.NaN);
        }
    }

    private record SensorValue(
        boolean isError,
        double value
    ) {}
}