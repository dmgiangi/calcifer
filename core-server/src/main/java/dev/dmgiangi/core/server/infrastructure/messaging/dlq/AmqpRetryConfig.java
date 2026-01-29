package dev.dmgiangi.core.server.infrastructure.messaging.dlq;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures retry behavior for AMQP message processing.
 *
 * <p>Retry strategy per Phase 0.16 decision:
 * <ul>
 *   <li>3 attempts (initial + 2 retries)</li>
 *   <li>Exponential backoff: 1s, 2s, 4s</li>
 *   <li>After exhaustion: reject without requeue → message goes to DLQ</li>
 * </ul>
 *
 * <p>This interceptor should be added to the advice chain of AMQP inbound adapters.
 */
@Configuration
public class AmqpRetryConfig {

    /**
     * Maximum number of retry attempts (not including the initial attempt).
     * Total attempts = MAX_RETRIES + 1 = 3 attempts.
     */
    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_INTERVAL_MS = 1000;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_INTERVAL_MS = 4000;

    /**
     * Creates a stateless retry interceptor for AMQP message processing.
     *
     * <p>On final failure, uses {@link RejectAndDontRequeueRecoverer} which:
     * <ul>
     *   <li>Logs the failure at WARN level</li>
     *   <li>Throws {@link org.springframework.amqp.AmqpRejectAndDontRequeueException}</li>
     *   <li>Causes the message to be rejected without requeue</li>
     *   <li>RabbitMQ routes the message to DLQ via DLX</li>
     * </ul>
     */
    @Bean
    public Advice amqpRetryAdvice() {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(MAX_RETRIES)
                .backOffOptions(INITIAL_INTERVAL_MS, MULTIPLIER, MAX_INTERVAL_MS)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}

