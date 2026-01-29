package dev.dmgiangi.core.server.infrastructure.messaging.dlq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors Dead Letter Queues (DLQ) for failed messages.
 *
 * <p>Listens to all DLQ queues and:
 * <ul>
 *   <li>Logs dead letters with full context (queue, reason, payload)</li>
 *   <li>Increments Micrometer counter for alerting</li>
 * </ul>
 *
 * <p>Metric: {@code calcifer.dlq.messages} with tag {@code queue} for the source queue name.
 */
@Slf4j
@Component
public class DeadLetterQueueMonitor {

    private static final String METRIC_NAME = "calcifer.dlq.messages";
    private static final String HEADER_X_DEATH = "x-death";
    private static final String HEADER_X_FIRST_DEATH_REASON = "x-first-death-reason";
    private static final String HEADER_X_FIRST_DEATH_QUEUE = "x-first-death-queue";

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    public DeadLetterQueueMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = DeadLetterConfig.TEMPERATURE_DLQ)
    public void handleTemperatureDlq(Message message) {
        handleDeadLetter(message, DeadLetterConfig.TEMPERATURE_DLQ);
    }

    @RabbitListener(queues = DeadLetterConfig.RELAY_FEEDBACK_DLQ)
    public void handleRelayFeedbackDlq(Message message) {
        handleDeadLetter(message, DeadLetterConfig.RELAY_FEEDBACK_DLQ);
    }

    @RabbitListener(queues = DeadLetterConfig.FAN_FEEDBACK_DLQ)
    public void handleFanFeedbackDlq(Message message) {
        handleDeadLetter(message, DeadLetterConfig.FAN_FEEDBACK_DLQ);
    }

    private void handleDeadLetter(Message message, String dlqName) {
        final var sourceQueue = extractSourceQueue(message, dlqName);
        final var deathReason = extractDeathReason(message);
        final var payload = extractPayload(message);
        final var routingKey = message.getMessageProperties().getReceivedRoutingKey();

        log.error("Dead letter received - queue: {}, reason: {}, routingKey: {}, payload: {}",
                sourceQueue, deathReason, routingKey, payload);

        // Increment counter for alerting
        getOrCreateCounter(sourceQueue).increment();
    }

    private String extractSourceQueue(Message message, String dlqName) {
        final var props = message.getMessageProperties();

        // Try x-first-death-queue header first (RabbitMQ 3.8+)
        final var firstDeathQueue = props.getHeader(HEADER_X_FIRST_DEATH_QUEUE);
        if (firstDeathQueue != null) {
            return firstDeathQueue.toString();
        }

        // Fall back to x-death header
        @SuppressWarnings("unchecked") final var xDeath = (java.util.List<Map<String, Object>>) props.getHeader(HEADER_X_DEATH);
        if (xDeath != null && !xDeath.isEmpty()) {
            final var firstDeath = xDeath.getFirst();
            final var queue = firstDeath.get("queue");
            if (queue != null) {
                return queue.toString();
            }
        }

        // Fall back to DLQ name without .dlq suffix
        return dlqName.replace(".dlq", "");
    }

    private String extractDeathReason(Message message) {
        final var props = message.getMessageProperties();

        // Try x-first-death-reason header first (RabbitMQ 3.8+)
        final var firstDeathReason = props.getHeader(HEADER_X_FIRST_DEATH_REASON);
        if (firstDeathReason != null) {
            return firstDeathReason.toString();
        }

        // Fall back to x-death header
        @SuppressWarnings("unchecked") final var xDeath = (java.util.List<Map<String, Object>>) props.getHeader(HEADER_X_DEATH);
        if (xDeath != null && !xDeath.isEmpty()) {
            final var firstDeath = xDeath.getFirst();
            final var reason = firstDeath.get("reason");
            if (reason != null) {
                return reason.toString();
            }
        }

        return "unknown";
    }

    private String extractPayload(Message message) {
        try {
            final var body = message.getBody();
            if (body == null || body.length == 0) {
                return "<empty>";
            }
            // Limit payload size for logging
            final var maxLength = 500;
            final var payloadStr = new String(body, StandardCharsets.UTF_8);
            if (payloadStr.length() > maxLength) {
                return payloadStr.substring(0, maxLength) + "... (truncated)";
            }
            return payloadStr;
        } catch (Exception e) {
            return "<binary data>";
        }
    }

    private Counter getOrCreateCounter(String sourceQueue) {
        return counterCache.computeIfAbsent(sourceQueue, queue ->
                Counter.builder(METRIC_NAME)
                        .description("Number of messages sent to Dead Letter Queue")
                        .tag("queue", queue)
                        .register(meterRegistry)
        );
    }
}

