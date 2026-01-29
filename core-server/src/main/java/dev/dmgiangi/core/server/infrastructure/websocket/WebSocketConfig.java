package dev.dmgiangi.core.server.infrastructure.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for real-time device state feedback.
 * Per Phase 0.7: STOMP over SockJS for real-time dashboards.
 *
 * <p>Topic structure:
 * <ul>
 *   <li>{@code /topic/devices/{controllerId}/{componentId}} - Device state updates</li>
 *   <li>{@code /topic/systems/{systemId}} - System-wide updates</li>
 *   <li>{@code /topic/overrides} - Override notifications</li>
 * </ul>
 *
 * <p>Message types per Phase 0.7:
 * <ul>
 *   <li>intent_accepted/rejected/modified</li>
 *   <li>desired_calculated</li>
 *   <li>device_converged/diverged</li>
 *   <li>override_applied/blocked/expired</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Configures the message broker for STOMP messaging.
     * Uses simple in-memory broker for MVP (can be replaced with RabbitMQ STOMP later).
     */
    @Override
    public void configureMessageBroker(final MessageBrokerRegistry config) {
        // Enable simple broker for topic subscriptions
        config.enableSimpleBroker("/topic");
        // Prefix for messages from clients to server (not used in this MVP)
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Registers STOMP endpoints with SockJS fallback.
     * Clients connect to /ws endpoint.
     */
    @Override
    public void registerStompEndpoints(final StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        // Also register without SockJS for native WebSocket clients
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }
}

