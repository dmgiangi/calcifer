package dev.dmgiangi.core.server.infrastructure.websocket.dto;

import java.time.Instant;

/**
 * WebSocket message for device state updates.
 * Per Phase 0.7: Real-time feedback for device state changes.
 *
 * @param deviceId      the device identifier (controllerId:componentId)
 * @param systemId      the system ID if device belongs to a system, null otherwise
 * @param messageType   the type of message (see {@link MessageType})
 * @param intentValue   the user's intended value (may be null)
 * @param desiredValue  the calculated desired value (may be null)
 * @param reportedValue the actual reported value from device (may be null)
 * @param isConverged   whether reported matches desired
 * @param reason        human-readable reason for the state change
 * @param timestamp     when this message was generated
 */
public record DeviceStateMessage(
        String deviceId,
        String systemId,
        MessageType messageType,
        Object intentValue,
        Object desiredValue,
        Object reportedValue,
        boolean isConverged,
        String reason,
        Instant timestamp
) {
    /**
     * Message types per Phase 0.7 decision.
     */
    public enum MessageType {
        /**
         * User intent was accepted without modification
         */
        INTENT_ACCEPTED,
        /**
         * User intent was rejected by safety rules
         */
        INTENT_REJECTED,
        /**
         * User intent was modified by safety rules
         */
        INTENT_MODIFIED,
        /**
         * New desired state was calculated
         */
        DESIRED_CALCULATED,
        /**
         * Device reported state matches desired state
         */
        DEVICE_CONVERGED,
        /**
         * Device reported state differs from desired state
         */
        DEVICE_DIVERGED,
        /**
         * Device reported its current state
         */
        STATE_REPORTED
    }

    /**
     * Creates a message for intent accepted.
     */
    public static DeviceStateMessage intentAccepted(
            final String deviceId,
            final String systemId,
            final Object intentValue,
            final String reason
    ) {
        return new DeviceStateMessage(
                deviceId, systemId, MessageType.INTENT_ACCEPTED,
                intentValue, null, null, false, reason, Instant.now()
        );
    }

    /**
     * Creates a message for intent rejected.
     */
    public static DeviceStateMessage intentRejected(
            final String deviceId,
            final String systemId,
            final Object intentValue,
            final String reason
    ) {
        return new DeviceStateMessage(
                deviceId, systemId, MessageType.INTENT_REJECTED,
                intentValue, null, null, false, reason, Instant.now()
        );
    }

    /**
     * Creates a message for intent modified.
     */
    public static DeviceStateMessage intentModified(
            final String deviceId,
            final String systemId,
            final Object originalIntent,
            final Object modifiedValue,
            final String reason
    ) {
        return new DeviceStateMessage(
                deviceId, systemId, MessageType.INTENT_MODIFIED,
                originalIntent, modifiedValue, null, false, reason, Instant.now()
        );
    }

    /**
     * Creates a message for desired state calculated.
     */
    public static DeviceStateMessage desiredCalculated(
            final String deviceId,
            final String systemId,
            final Object desiredValue,
            final String reason
    ) {
        return new DeviceStateMessage(
                deviceId, systemId, MessageType.DESIRED_CALCULATED,
                null, desiredValue, null, false, reason, Instant.now()
        );
    }

    /**
     * Creates a message for device converged.
     */
    public static DeviceStateMessage deviceConverged(
            final String deviceId,
            final String systemId,
            final Object value
    ) {
        return new DeviceStateMessage(
                deviceId, systemId, MessageType.DEVICE_CONVERGED,
                null, value, value, true, "Device state matches desired state", Instant.now()
        );
    }

    /**
     * Creates a message for device diverged.
     */
    public static DeviceStateMessage deviceDiverged(
            final String deviceId,
            final String systemId,
            final Object desiredValue,
            final Object reportedValue
    ) {
        return new DeviceStateMessage(
                deviceId, systemId, MessageType.DEVICE_DIVERGED,
                null, desiredValue, reportedValue, false,
                "Device state differs from desired state", Instant.now()
        );
    }

    /**
     * Creates a message for state reported.
     */
    public static DeviceStateMessage stateReported(
            final String deviceId,
            final String systemId,
            final Object reportedValue,
            final Object desiredValue,
            final boolean isConverged
    ) {
        return new DeviceStateMessage(
                deviceId, systemId, MessageType.STATE_REPORTED,
                null, desiredValue, reportedValue, isConverged,
                isConverged ? "Device converged" : "Device diverged", Instant.now()
        );
    }
}

