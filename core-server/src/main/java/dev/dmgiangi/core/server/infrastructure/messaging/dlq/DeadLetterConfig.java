package dev.dmgiangi.core.server.infrastructure.messaging.dlq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dead Letter Queue (DLQ) configuration for AMQP message handling.
 *
 * <p>Provides a shared Dead Letter Exchange (DLX) and DLQ queues for all inbound queues.
 * Messages that fail processing after retry attempts are routed to their respective DLQ
 * for investigation and potential reprocessing.
 *
 * <p>DLQ naming convention: {@code <original-queue-name>.dlq}
 */
@Configuration
public class DeadLetterConfig {

    public static final String DLX_EXCHANGE_NAME = "dlx.exchange";

    // DLQ queue names
    public static final String TEMPERATURE_DLQ = "mqtt.temperature.queue.dlq";
    public static final String RELAY_FEEDBACK_DLQ = "mqtt.relay.feedback.queue.dlq";
    public static final String FAN_FEEDBACK_DLQ = "mqtt.fan.feedback.queue.dlq";

    /**
     * Shared Dead Letter Exchange (DLX) for all queues.
     * Uses DirectExchange for precise routing to specific DLQ queues.
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE_NAME, true, false);
    }

    // ==================== Temperature DLQ ====================

    @Bean
    public Queue temperatureDeadLetterQueue() {
        return QueueBuilder.durable(TEMPERATURE_DLQ).build();
    }

    @Bean
    public Binding temperatureDlqBinding(Queue temperatureDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(temperatureDeadLetterQueue)
                .to(deadLetterExchange)
                .with(TEMPERATURE_DLQ);
    }

    // ==================== Relay Feedback DLQ ====================

    @Bean
    public Queue relayFeedbackDeadLetterQueue() {
        return QueueBuilder.durable(RELAY_FEEDBACK_DLQ).build();
    }

    @Bean
    public Binding relayFeedbackDlqBinding(Queue relayFeedbackDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(relayFeedbackDeadLetterQueue)
                .to(deadLetterExchange)
                .with(RELAY_FEEDBACK_DLQ);
    }

    // ==================== Fan Feedback DLQ ====================

    @Bean
    public Queue fanFeedbackDeadLetterQueue() {
        return QueueBuilder.durable(FAN_FEEDBACK_DLQ).build();
    }

    @Bean
    public Binding fanFeedbackDlqBinding(Queue fanFeedbackDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(fanFeedbackDeadLetterQueue)
                .to(deadLetterExchange)
                .with(FAN_FEEDBACK_DLQ);
    }
}

