package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.safety.SafetyContext;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link SafetyValidator}.
 * Wraps {@link SafetyRuleEngine} and handles context building and related device loading.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Build SafetyContext from input parameters</li>
 *   <li>Load related device states for interlock rules (e.g., pump-fire interlock)</li>
 *   <li>Delegate to SafetyRuleEngine for actual evaluation</li>
 *   <li>Handle SpEL failures by falling back to hardcoded rules</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSafetyValidator implements SafetyValidator {

    private final SafetyRuleEngine safetyRuleEngine;
    private final DeviceStateRepository deviceStateRepository;

    @Override
    public SafetyEvaluationResult validate(
            final DeviceId deviceId,
            final DeviceValue proposedValue,
            final DeviceTwinSnapshot snapshot,
            final FunctionalSystemData system
    ) {
        return validate(deviceId, proposedValue, snapshot, system, Map.of());
    }

    @Override
    public SafetyEvaluationResult validate(
            final DeviceId deviceId,
            final DeviceValue proposedValue,
            final DeviceTwinSnapshot snapshot,
            final FunctionalSystemData system,
            final Map<String, Object> metadata
    ) {
        Objects.requireNonNull(deviceId, "Device ID must not be null");
        Objects.requireNonNull(proposedValue, "Proposed value must not be null");

        log.debug("Validating safety for device {} with proposed value {}", deviceId, proposedValue);

        try {
            final var context = buildSafetyContext(deviceId, proposedValue, snapshot, system, metadata);
            return safetyRuleEngine.evaluate(context);
        } catch (Exception e) {
            log.error("Safety evaluation failed for device {}, falling back to hardcoded rules: {}",
                    deviceId, e.getMessage(), e);
            return validateHardcodedOnly(deviceId, proposedValue, snapshot);
        }
    }

    @Override
    public SafetyEvaluationResult validateHardcodedOnly(
            final DeviceId deviceId,
            final DeviceValue proposedValue,
            final DeviceTwinSnapshot snapshot
    ) {
        Objects.requireNonNull(deviceId, "Device ID must not be null");
        Objects.requireNonNull(proposedValue, "Proposed value must not be null");

        log.debug("Validating hardcoded rules only for device {}", deviceId);

        final var context = buildMinimalSafetyContext(deviceId, proposedValue, snapshot);
        return safetyRuleEngine.evaluateHardcodedOnly(context);
    }

    /**
     * Builds a complete SafetyContext including related device states.
     */
    private SafetyContext buildSafetyContext(
            final DeviceId deviceId,
            final DeviceValue proposedValue,
            final DeviceTwinSnapshot snapshot,
            final FunctionalSystemData system,
            final Map<String, Object> metadata
    ) {
        final var deviceType = deriveDeviceType(proposedValue, snapshot);

        final var builder = SafetyContext.builder()
                .deviceId(deviceId)
                .deviceType(deviceType)
                .currentSnapshot(snapshot)
                .proposedValue(proposedValue)
                .functionalSystem(system)
                .metadata(metadata != null ? metadata : Map.of());

        // Load related device states for interlock rules
        if (system != null && system.deviceIds() != null && !system.deviceIds().isEmpty()) {
            final var relatedStates = loadRelatedDeviceStates(deviceId, system);
            builder.relatedDeviceStates(relatedStates);
        }

        return builder.build();
    }

    /**
     * Builds a minimal SafetyContext for hardcoded-only evaluation.
     */
    private SafetyContext buildMinimalSafetyContext(
            final DeviceId deviceId,
            final DeviceValue proposedValue,
            final DeviceTwinSnapshot snapshot
    ) {
        final var deviceType = deriveDeviceType(proposedValue, snapshot);

        return SafetyContext.builder()
                .deviceId(deviceId)
                .deviceType(deviceType)
                .currentSnapshot(snapshot)
                .proposedValue(proposedValue)
                .build();
    }

    /**
     * Derives the DeviceType from the snapshot or the proposed value type.
     * Priority: snapshot.type() > infer from DeviceValue class
     */
    private DeviceType deriveDeviceType(final DeviceValue proposedValue, final DeviceTwinSnapshot snapshot) {
        if (snapshot != null) {
            return snapshot.type();
        }
        // Infer from DeviceValue implementation
        return switch (proposedValue) {
            case RelayValue ignored -> DeviceType.RELAY;
            case FanValue ignored -> DeviceType.FAN;
        };
    }

    /**
     * Loads twin snapshots for all related devices in the same FunctionalSystem.
     * Excludes the device being validated.
     */
    private Map<DeviceId, DeviceTwinSnapshot> loadRelatedDeviceStates(
            final DeviceId excludeDeviceId,
            final FunctionalSystemData system
    ) {
        final var relatedStates = new HashMap<DeviceId, DeviceTwinSnapshot>();
        final var excludeKey = excludeDeviceId.controllerId() + ":" + excludeDeviceId.componentId();

        for (final var deviceIdStr : system.deviceIds()) {
            if (deviceIdStr.equals(excludeKey)) {
                continue; // Skip the device being validated
            }

            try {
                final var relatedDeviceId = parseDeviceId(deviceIdStr);
                deviceStateRepository.findTwinSnapshot(relatedDeviceId)
                        .ifPresent(snapshot -> relatedStates.put(relatedDeviceId, snapshot));
            } catch (Exception e) {
                log.warn("Failed to load related device state for {}: {}", deviceIdStr, e.getMessage());
            }
        }

        log.trace("Loaded {} related device states for system {}", relatedStates.size(), system.id());
        return relatedStates;
    }

    /**
     * Parses a device ID string (format: "controllerId:componentId") into a DeviceId.
     */
    private DeviceId parseDeviceId(final String deviceIdStr) {
        final var parts = deviceIdStr.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid device ID format: " + deviceIdStr);
        }
        return new DeviceId(parts[0], parts[1]);
    }
}

