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
 * AMQP flow configuration for receiving FAN actuator feedback.
 * Listens to fan state topics and publishes ActuatorFeedbackReceivedEvent.
 *
 * <p>MQTT Topic: {@code /<clientId>/fan/<name>/state}
 * <p>AMQP Routing Key: {@code *.*.fan.*.state}
 */
@Slf4j
@Configuration
public class FanFeedbackAmqpFlowConfig {

    public static final String QUEUE_NAME = "mqtt.fan.feedback.queue";
    public static final String ROUTING_KEY = "*.*.fan.*.state";

    @Bean
    public Queue fanFeedbackQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", DeadLetterConfig.DLX_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", DeadLetterConfig.FAN_FEEDBACK_DLQ)
                .build();
    }

    @Bean
    public Binding fanFeedbackBinding(Queue fanFeedbackQueue, TopicExchange mqttExchange) {
        return BindingBuilder.bind(fanFeedbackQueue).to(mqttExchange).with(ROUTING_KEY);
    }

    @Bean
    public IntegrationFlow fanFeedbackFlow(
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
                    log.debug("Publishing FAN feedback event: {}", feedback);
                    eventPublisher.publishEvent(new ActuatorFeedbackReceivedEvent(message, feedback));
                })
                .get();
    }
}

