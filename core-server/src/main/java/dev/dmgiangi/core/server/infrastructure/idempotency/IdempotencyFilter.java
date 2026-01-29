package dev.dmgiangi.core.server.infrastructure.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.core.GenericSelector;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * Spring Integration filter for idempotent message processing.
 *
 * <p>Per Phase 0.18 decision: Enforcement point is IntegrationFlow filter
 * (before event publishing) to prevent wasted processing.
 *
 * <p>Idempotency key extraction strategy:
 * <ol>
 *   <li>Try AMQP messageId header (amqp_messageId)</li>
 *   <li>Fallback: generate content hash from routingKey + payload</li>
 * </ol>
 *
 * <p>Usage: Add as .filter() step in IntegrationFlow before event publishing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyFilter implements GenericSelector<Message<?>> {

    private static final String HEADER_MESSAGE_ID = "amqp_messageId";
    private static final String HEADER_ROUTING_KEY = "amqp_receivedRoutingKey";
    private static final String HEADER_TIMESTAMP = "amqp_timestamp";

    private final IdempotencyService idempotencyService;

    /**
     * Filters messages based on idempotency.
     *
     * @param message the incoming message
     * @return true if message should be processed (first time), false if duplicate
     */
    @Override
    public boolean accept(Message<?> message) {
        final var idempotencyKey = extractIdempotencyKey(message);
        return idempotencyService.tryAcquire(idempotencyKey);
    }

    /**
     * Extracts or generates an idempotency key from the message.
     *
     * @param message the incoming message
     * @return the idempotency key
     */
    private String extractIdempotencyKey(Message<?> message) {
        // Strategy 1: Use AMQP messageId if available
        final var messageId = message.getHeaders().get(HEADER_MESSAGE_ID, String.class);
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }

        // Strategy 2: Generate content hash from routing key + payload + timestamp
        final var routingKey = message.getHeaders().get(HEADER_ROUTING_KEY, String.class);
        final var timestamp = message.getHeaders().get(HEADER_TIMESTAMP, Long.class);
        final var payload = extractPayloadString(message);

        // Use routing key as device identifier (contains clientId and componentId)
        final var deviceId = routingKey != null ? routingKey : "unknown";
        final var ts = timestamp != null ? timestamp : System.currentTimeMillis();

        return idempotencyService.generateContentHash(deviceId, ts, payload);
    }

    /**
     * Extracts payload as string for hashing.
     */
    private String extractPayloadString(Message<?> message) {
        final var payload = message.getPayload();

        if (payload instanceof byte[] bytes) {
            return new String(bytes);
        } else if (payload instanceof String str) {
            return str;
        } else {
            return payload.toString();
        }
    }
}

