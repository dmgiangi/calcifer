package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;

import java.util.Map;
import java.util.Optional;

/**
 * Pure function interface for calculating the desired device state.
 * This is the core business logic component that transforms inputs into DesiredState.
 *
 * <p>Calculation priority (per Phase 0.4/0.5):
 * <ol>
 *   <li>Safety rules (HARDCODED_SAFETY, SYSTEM_SAFETY) - cannot be overridden</li>
 *   <li>Active overrides (EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL)</li>
 *   <li>User intent</li>
 * </ol>
 *
 * <p>This is a pure function with no side effects - it only computes the result
 * based on inputs. Persistence and event publishing are handled by the caller.
 */
public interface StateCalculator {

    /**
     * Calculates the desired state for a device.
     *
     * @param snapshot the current device twin snapshot
     * @param system   the FunctionalSystem this device belongs to (may be null for standalone devices)
     * @return Optional containing the calculated desired state, or empty if no state can be calculated
     */
    Optional<DesiredDeviceState> calculate(DeviceTwinSnapshot snapshot, FunctionalSystemData system);

    /**
     * Calculates the desired state with additional metadata context.
     *
     * @param snapshot the current device twin snapshot
     * @param system   the FunctionalSystem this device belongs to (may be null for standalone devices)
     * @param metadata additional context data (e.g., temperature readings)
     * @return Optional containing the calculated desired state, or empty if no state can be calculated
     */
    Optional<DesiredDeviceState> calculate(
            DeviceTwinSnapshot snapshot,
            FunctionalSystemData system,
            Map<String, Object> metadata
    );

    /**
     * Result of state calculation with detailed information about the decision.
     *
     * @param desiredState  the calculated desired state (may be null if no state calculated)
     * @param source        the source of the value (INTENT, OVERRIDE, SAFETY_MODIFIED, SAFETY_REFUSED)
     * @param originalValue the original value before any modifications (may be null)
     * @param reason        human-readable reason for the decision
     */
    record CalculationResult(
            DesiredDeviceState desiredState,
            ValueSource source,
            DeviceValue originalValue,
            String reason
    ) {
        /**
         * Source of the calculated value.
         */
        public enum ValueSource {
            /**
             * Value comes directly from user intent
             */
            INTENT,
            /**
             * Value comes from an active override
             */
            OVERRIDE,
            /**
             * Value was modified by safety rules
             */
            SAFETY_MODIFIED,
            /**
             * Original value was refused by safety rules, using fallback
             */
            SAFETY_REFUSED,
            /**
             * No value could be calculated (no intent, no override)
             */
            NO_VALUE
        }

        public boolean hasValue() {
            return desiredState != null;
        }

        public static CalculationResult fromIntent(DesiredDeviceState state) {
            return new CalculationResult(state, ValueSource.INTENT, state.value(), "User intent applied");
        }

        public static CalculationResult fromOverride(DesiredDeviceState state, String reason) {
            return new CalculationResult(state, ValueSource.OVERRIDE, state.value(), reason);
        }

        public static CalculationResult safetyModified(DesiredDeviceState state, DeviceValue original, String reason) {
            return new CalculationResult(state, ValueSource.SAFETY_MODIFIED, original, reason);
        }

        public static CalculationResult safetyRefused(String reason) {
            return new CalculationResult(null, ValueSource.SAFETY_REFUSED, null, reason);
        }

        public static CalculationResult noValue(String reason) {
            return new CalculationResult(null, ValueSource.NO_VALUE, null, reason);
        }
    }

    /**
     * Calculates the desired state with full result details.
     * Use this method when you need information about why a particular value was chosen.
     *
     * @param snapshot the current device twin snapshot
     * @param system   the FunctionalSystem this device belongs to (may be null)
     * @param metadata additional context data
     * @return the calculation result with details
     */
    CalculationResult calculateWithDetails(
            DeviceTwinSnapshot snapshot,
            FunctionalSystemData system,
            Map<String, Object> metadata
    );
}

