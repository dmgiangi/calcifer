package dev.dmgiangi.core.server.infrastructure.messaging.outbound;

import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import dev.dmgiangi.core.server.infrastructure.messaging.outbound.trasformer.CommandPayloadTransformer;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.event.inbound.ApplicationEventListeningMessageProducer;


@Configuration
@RequiredArgsConstructor
public class MqttCommandOutboundFlowConfig {

    @Bean
    public IntegrationFlow mqttCommandFlow(
            ApplicationEventListeningMessageProducer deviceCommandEventProducer,
            CommandPayloadTransformer commandPayloadTransformer,
            AmqpTemplate amqpTemplate) {
        return IntegrationFlow
                .from(deviceCommandEventProducer)
                .transform(commandPayloadTransformer)
                .split()
                .handle(Amqp
                        .outboundAdapter(amqpTemplate)
                        .exchangeName("amq.topic")
                        .routingKeyFunction(msg -> msg.getHeaders().get("mqtt_routing_key", String.class)))
                .get();
    }

    @Bean
    public ApplicationEventListeningMessageProducer deviceCommandEventProducer() {
        final var producer = new ApplicationEventListeningMessageProducer();
        producer.setEventTypes(DeviceCommandEvent.class);
        return producer;
    }
}