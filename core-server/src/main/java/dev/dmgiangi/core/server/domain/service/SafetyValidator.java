package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;

import java.util.Map;

/**
 * Domain service port for safety validation.
 * Validates proposed device state changes against safety rules.
 *
 * <p>This is a higher-level API that wraps {@link SafetyRuleEngine} and handles:
 * <ul>
 *   <li>Building {@link dev.dmgiangi.core.server.domain.model.safety.SafetyContext} from inputs</li>
 *   <li>Loading related device states for interlock rules</li>
 *   <li>Fallback to hardcoded rules when SpEL evaluation fails</li>
 * </ul>
 *
 * <p>Per Phase 0.4: Safety rules have fixed precedence:
 * HARDCODED_SAFETY > SYSTEM_SAFETY > EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL > USER_INTENT
 */
public interface SafetyValidator {

    /**
     * Validates a proposed device state change against safety rules.
     *
     * @param deviceId      the device being changed
     * @param proposedValue the proposed new value
     * @param snapshot      the current device twin snapshot (may be null for new devices)
     * @param system        the FunctionalSystem this device belongs to (may be null for standalone devices)
     * @return the validation result (accepted, refused, or modified)
     */
    SafetyEvaluationResult validate(
            DeviceId deviceId,
            DeviceValue proposedValue,
            DeviceTwinSnapshot snapshot,
            FunctionalSystemData system
    );

    /**
     * Validates a proposed device state change with additional metadata.
     * Use this overload when additional context is needed (e.g., temperature readings).
     *
     * @param deviceId      the device being changed
     * @param proposedValue the proposed new value
     * @param snapshot      the current device twin snapshot (may be null for new devices)
     * @param system        the FunctionalSystem this device belongs to (may be null for standalone devices)
     * @param metadata      additional context data (e.g., temperature readings, timestamps)
     * @return the validation result (accepted, refused, or modified)
     */
    SafetyEvaluationResult validate(
            DeviceId deviceId,
            DeviceValue proposedValue,
            DeviceTwinSnapshot snapshot,
            FunctionalSystemData system,
            Map<String, Object> metadata
    );

    /**
     * Validates using only hardcoded safety rules.
     * Use this as a fallback when the full rule engine is unavailable.
     *
     * @param deviceId      the device being changed
     * @param proposedValue the proposed new value
     * @param snapshot      the current device twin snapshot
     * @return the validation result from hardcoded rules only
     */
    SafetyEvaluationResult validateHardcodedOnly(
            DeviceId deviceId,
            DeviceValue proposedValue,
            DeviceTwinSnapshot snapshot
    );
}

