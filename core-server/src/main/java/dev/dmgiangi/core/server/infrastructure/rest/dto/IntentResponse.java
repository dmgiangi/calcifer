package dev.dmgiangi.core.server.infrastructure.rest.dto;

import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.UserIntent;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;

import java.time.Instant;

/**
 * Response DTO for intent submission.
 * Provides feedback about the submitted intent and system context.
 *
 * <p>Per Phase 5.8: Includes system information when device belongs to a FunctionalSystem,
 * indicating that the intent will be processed through the LogicEngine with safety rules.
 *
 * @param deviceId    the device identifier (format: controllerId:componentId)
 * @param systemId    the FunctionalSystem ID if device belongs to one, null if standalone
 * @param systemName  the FunctionalSystem name if device belongs to one, null if standalone
 * @param type        the device type
 * @param value       the raw intent value
 * @param requestedAt when the intent was submitted
 * @param message     informational message about processing
 */
public record IntentResponse(
        String deviceId,
        String systemId,
        String systemName,
        DeviceType type,
        Object value,
        Instant requestedAt,
        String message
) {

    private static final String STANDALONE_MESSAGE = "Intent saved. Device is standalone - direct passthrough to desired state.";
    private static final String SYSTEM_MESSAGE_TEMPLATE = "Intent saved. Device belongs to system '%s' - will be processed through safety rules.";

    /**
     * Creates a response for a standalone device (not in any FunctionalSystem).
     *
     * @param intent the saved user intent
     * @return the response DTO
     */
    public static IntentResponse standalone(final UserIntent intent) {
        return new IntentResponse(
                formatDeviceId(intent),
                null,
                null,
                intent.type(),
                extractRawValue(intent),
                intent.requestedAt(),
                STANDALONE_MESSAGE
        );
    }

    /**
     * Creates a response for a device that belongs to a FunctionalSystem.
     *
     * @param intent the saved user intent
     * @param system the FunctionalSystem the device belongs to
     * @return the response DTO
     */
    public static IntentResponse inSystem(final UserIntent intent, final FunctionalSystemData system) {
        return new IntentResponse(
                formatDeviceId(intent),
                system.id(),
                system.name(),
                intent.type(),
                extractRawValue(intent),
                intent.requestedAt(),
                String.format(SYSTEM_MESSAGE_TEMPLATE, system.name())
        );
    }

    /**
     * Formats the device ID as controllerId:componentId.
     */
    private static String formatDeviceId(final UserIntent intent) {
        return intent.id().controllerId() + ":" + intent.id().componentId();
    }

    /**
     * Extracts the raw value from the intent for JSON serialization.
     */
    private static Object extractRawValue(final UserIntent intent) {
        final var value = intent.value();
        if (value == null) return null;
        return switch (value) {
            case dev.dmgiangi.core.server.domain.model.RelayValue r -> r.state();
            case dev.dmgiangi.core.server.domain.model.FanValue f -> f.speed();
        };
    }
}

