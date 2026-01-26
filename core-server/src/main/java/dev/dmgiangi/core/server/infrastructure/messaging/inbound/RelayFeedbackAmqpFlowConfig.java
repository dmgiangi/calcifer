package dev.dmgiangi.core.server.infrastructure.messaging.inbound;

import dev.dmgiangi.core.server.domain.model.ActuatorFeedback;
import dev.dmgiangi.core.server.domain.model.event.ActuatorFeedbackReceivedEvent;
import dev.dmgiangi.core.server.infrastructure.messaging.inbound.trasformer.AmqpToActuatorFeedbackTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.dsl.IntegrationFlow;

/**
 * AMQP flow configuration for receiving RELAY actuator feedback.
 * Listens to digital_output state topics and publishes ActuatorFeedbackReceivedEvent.
 *
 * <p>MQTT Topic: {@code /<clientId>/digital_output/<name>/state}
 * <p>AMQP Routing Key: {@code *.*.digital_output.*.state}
 */
@Slf4j
@Configuration
public class RelayFeedbackAmqpFlowConfig {

    public static final String QUEUE_NAME = "mqtt.relay.feedback.queue";
    public static final String ROUTING_KEY = "*.*.digital_output.*.state";

    @Bean
    public Queue relayFeedbackQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding relayFeedbackBinding(Queue relayFeedbackQueue, TopicExchange mqttExchange) {
        return BindingBuilder.bind(relayFeedbackQueue).to(mqttExchange).with(ROUTING_KEY);
    }

    @Bean
    public IntegrationFlow relayFeedbackFlow(
        ConnectionFactory connectionFactory,
        AmqpToActuatorFeedbackTransformer actuatorFeedbackTransformer,
        ApplicationEventPublisher eventPublisher) {
        return IntegrationFlow
            .from(Amqp.inboundAdapter(connectionFactory, QUEUE_NAME))
            .transform(actuatorFeedbackTransformer)
            .handle(message -> {
                final var feedback = (ActuatorFeedback) message.getPayload();
                log.debug("Publishing RELAY feedback event: {}", feedback);
                eventPublisher.publishEvent(new ActuatorFeedbackReceivedEvent(message, feedback));
            })
            .get();
    }
}

