package dev.dmgiangi.core.server.infrastructure.messaging.inbound;

import dev.dmgiangi.core.server.domain.model.ActuatorFeedback;
import dev.dmgiangi.core.server.domain.model.event.ActuatorFeedbackReceivedEvent;
import dev.dmgiangi.core.server.infrastructure.idempotency.IdempotencyFilter;
import dev.dmgiangi.core.server.infrastructure.messaging.dlq.DeadLetterConfig;
import dev.dmgiangi.core.server.infrastructure.messaging.inbound.trasformer.AmqpToActuatorFeedbackTransformer;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
        return QueueBuilder.durable(QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", DeadLetterConfig.DLX_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", DeadLetterConfig.RELAY_FEEDBACK_DLQ)
                .build();
    }

    @Bean
    public Binding relayFeedbackBinding(Queue relayFeedbackQueue, TopicExchange mqttExchange) {
        return BindingBuilder.bind(relayFeedbackQueue).to(mqttExchange).with(ROUTING_KEY);
    }

    @Bean
    public IntegrationFlow relayFeedbackFlow(
            ConnectionFactory connectionFactory,
            AmqpToActuatorFeedbackTransformer actuatorFeedbackTransformer,
            ApplicationEventPublisher eventPublisher,
            IdempotencyFilter idempotencyFilter,
            @Qualifier("amqpRetryAdvice") Advice retryAdvice) {
        return IntegrationFlow
                .from(Amqp.inboundAdapter(connectionFactory, QUEUE_NAME)
                        .configureContainer(c -> c.adviceChain(retryAdvice)))
                .filter(idempotencyFilter)
                .transform(actuatorFeedbackTransformer)
                .handle(message -> {
                    final var feedback = (ActuatorFeedback) message.getPayload();
                    log.debug("Publishing RELAY feedback event: {}", feedback);
                    eventPublisher.publishEvent(new ActuatorFeedbackReceivedEvent(message, feedback));
                })
                .get();
    }
}

