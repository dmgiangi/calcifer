package dev.dmgiangi.core.server.infrastructure.websocket;

import dev.dmgiangi.core.server.application.override.OverrideApplicationService.OverrideAppliedEvent;
import dev.dmgiangi.core.server.application.override.OverrideApplicationService.OverrideCancelledEvent;
import dev.dmgiangi.core.server.application.override.OverrideExpirationService.OverrideExpiredEvent;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.event.DesiredStateCalculatedEvent;
import dev.dmgiangi.core.server.domain.model.event.ReportedStateChangedEvent;
import dev.dmgiangi.core.server.domain.model.event.UserIntentChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;
import dev.dmgiangi.core.server.domain.service.DeviceSystemMappingService;
import dev.dmgiangi.core.server.infrastructure.websocket.dto.DeviceStateMessage;
import dev.dmgiangi.core.server.infrastructure.websocket.dto.OverrideMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service that bridges domain events to WebSocket topics.
 * Per Phase 0.7: Real-time feedback for device state changes and overrides.
 *
 * <p>Listens to:
 * <ul>
 *   <li>{@link UserIntentChangedEvent} - User submitted new intent</li>
 *   <li>{@link DesiredStateCalculatedEvent} - New desired state calculated</li>
 *   <li>{@link ReportedStateChangedEvent} - Device reported its state</li>
 *   <li>{@link OverrideAppliedEvent} - Override was applied</li>
 *   <li>{@link OverrideCancelledEvent} - Override was cancelled</li>
 *   <li>{@link OverrideExpiredEvent} - Override expired</li>
 * </ul>
 *
 * <p>Publishes to:
 * <ul>
 *   <li>{@code /topic/devices/{controllerId}/{componentId}} - Device-specific updates</li>
 *   <li>{@code /topic/systems/{systemId}} - System-wide updates</li>
 *   <li>{@code /topic/overrides} - All override notifications</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceStateWebSocketService {

    private final SimpMessagingTemplate messagingTemplate;
    private final DeviceSystemMappingService deviceSystemMappingService;
    private final DeviceStateRepository deviceStateRepository;

    // ==================== Intent Events ====================

    @Async
    @EventListener
    public void onUserIntentChanged(final UserIntentChangedEvent event) {
        final var intent = event.getIntent();
        final var deviceId = intent.id();
        final var deviceIdStr = formatDeviceId(deviceId);
        final var systemId = findSystemId(deviceId);

        log.debug("Publishing intent changed for device {} to WebSocket", deviceIdStr);

        final var message = DeviceStateMessage.intentAccepted(
                deviceIdStr,
                systemId,
                extractValue(intent.value()),
                "User intent received"
        );

        publishToDevice(deviceId, message);
        publishToSystem(systemId, message);
    }

    // ==================== Desired State Events ====================

    @Async
    @EventListener
    public void onDesiredStateCalculated(final DesiredStateCalculatedEvent event) {
        final var desired = event.getDesiredState();
        final var deviceId = desired.id();
        final var deviceIdStr = formatDeviceId(deviceId);
        final var systemId = findSystemId(deviceId);

        log.debug("Publishing desired state calculated for device {} to WebSocket", deviceIdStr);

        final var message = DeviceStateMessage.desiredCalculated(
                deviceIdStr,
                systemId,
                extractValue(desired.value()),
                "Desired state calculated"
        );

        publishToDevice(deviceId, message);
        publishToSystem(systemId, message);
    }

    // ==================== Reported State Events ====================

    @Async
    @EventListener
    public void onReportedStateChanged(final ReportedStateChangedEvent event) {
        final var reported = event.getReportedState();
        final var deviceId = reported.id();
        final var deviceIdStr = formatDeviceId(deviceId);
        final var systemId = findSystemId(deviceId);

        log.debug("Publishing reported state for device {} to WebSocket", deviceIdStr);

        // Check convergence with desired state
        final var desiredOpt = deviceStateRepository.findDesiredState(deviceId);
        final var isConverged = desiredOpt
                .map(desired -> desired.value().equals(reported.value()))
                .orElse(false);

        final var message = DeviceStateMessage.stateReported(
                deviceIdStr,
                systemId,
                reported.isKnown() ? extractValue(reported.value()) : null,
                desiredOpt.map(d -> extractValue(d.value())).orElse(null),
                isConverged
        );

        publishToDevice(deviceId, message);
        publishToSystem(systemId, message);
    }

    // ==================== Override Events ====================

    @Async
    @EventListener
    public void onOverrideApplied(final OverrideAppliedEvent event) {
        log.debug("Publishing override applied for target {} to WebSocket", event.targetId());

        // Determine scope from targetId format (device has ":", system doesn't)
        final var scope = event.targetId().contains(":")
                ? OverrideMessage.Scope.DEVICE
                : OverrideMessage.Scope.SYSTEM;

        final var message = OverrideMessage.applied(
                event.targetId(),
                scope,
                event.category(),
                null, // Value not available in event
                event.wasModified(),
                event.warnings()
        );

        publishOverride(message);
    }

    @Async
    @EventListener
    public void onOverrideCancelled(final OverrideCancelledEvent event) {
        log.debug("Publishing override cancelled for target {} to WebSocket", event.targetId());

        final var scope = event.targetId().contains(":")
                ? OverrideMessage.Scope.DEVICE
                : OverrideMessage.Scope.SYSTEM;

        final var message = OverrideMessage.cancelled(
                event.targetId(),
                scope,
                event.category()
        );

        publishOverride(message);
    }

    @Async
    @EventListener
    public void onOverrideExpired(final OverrideExpiredEvent event) {
        log.debug("Publishing override expired for target {} to WebSocket", event.targetId());

        final var scope = event.scope() == OverrideScope.DEVICE
                ? OverrideMessage.Scope.DEVICE
                : OverrideMessage.Scope.SYSTEM;

        final var message = OverrideMessage.expired(
                event.targetId(),
                scope,
                event.category()
        );

        publishOverride(message);
    }

    // ==================== Publishing Helpers ====================

    private void publishToDevice(final DeviceId deviceId, final DeviceStateMessage message) {
        final var topic = "/topic/devices/" + deviceId.controllerId() + "/" + deviceId.componentId();
        try {
            messagingTemplate.convertAndSend(topic, message);
            log.trace("Published message to {}", topic);
        } catch (Exception e) {
            log.warn("Failed to publish to WebSocket topic {}: {}", topic, e.getMessage());
        }
    }

    private void publishToSystem(final String systemId, final DeviceStateMessage message) {
        if (systemId == null) {
            return;
        }
        final var topic = "/topic/systems/" + systemId;
        try {
            messagingTemplate.convertAndSend(topic, message);
            log.trace("Published message to {}", topic);
        } catch (Exception e) {
            log.warn("Failed to publish to WebSocket topic {}: {}", topic, e.getMessage());
        }
    }

    private void publishOverride(final OverrideMessage message) {
        final var topic = "/topic/overrides";
        try {
            messagingTemplate.convertAndSend(topic, message);
            log.trace("Published override message to {}", topic);
        } catch (Exception e) {
            log.warn("Failed to publish to WebSocket topic {}: {}", topic, e.getMessage());
        }
    }

    // ==================== Utility Methods ====================

    private String formatDeviceId(final DeviceId deviceId) {
        return deviceId.controllerId() + ":" + deviceId.componentId();
    }

    private String findSystemId(final DeviceId deviceId) {
        return deviceSystemMappingService.findSystemIdByDevice(deviceId)
                .map(id -> id.value().toString())
                .orElse(null);
    }

    private Object extractValue(final Object value) {
        if (value == null) {
            return null;
        }
        // Extract raw value from DeviceValue types for JSON serialization
        return switch (value) {
            case dev.dmgiangi.core.server.domain.model.RelayValue rv -> rv.state();
            case dev.dmgiangi.core.server.domain.model.FanValue fv -> fv.speed();
            default -> value;
        };
    }
}

