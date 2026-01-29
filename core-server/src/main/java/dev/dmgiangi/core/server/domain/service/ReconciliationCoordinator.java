package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;

import java.util.Map;
import java.util.Optional;

/**
 * Coordinator for the reconciliation process.
 * This is the side-effect component that orchestrates state calculation, persistence, and event publishing.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load device snapshot and FunctionalSystem context</li>
 *   <li>Delegate calculation to {@link StateCalculator}</li>
 *   <li>Persist the calculated desired state</li>
 *   <li>Publish domain events (DesiredStateCalculatedEvent)</li>
 *   <li>Handle infrastructure failures per Phase 0.3 (fail-stop pattern)</li>
 * </ul>
 *
 * <p>This component has side effects (persistence, events) unlike the pure {@link StateCalculator}.
 */
public interface ReconciliationCoordinator {

    /**
     * Reconciles a device's desired state based on current intent and safety rules.
     *
     * <p>Flow:
     * <ol>
     *   <li>Load device twin snapshot</li>
     *   <li>Load FunctionalSystem if device belongs to one</li>
     *   <li>Calculate desired state via StateCalculator</li>
     *   <li>Persist the new desired state</li>
     *   <li>Publish DesiredStateCalculatedEvent</li>
     * </ol>
     *
     * @param deviceId the device to reconcile
     * @return the reconciliation result with details
     */
    ReconciliationResult reconcile(DeviceId deviceId);

    /**
     * Reconciles a device with additional metadata context.
     *
     * @param deviceId the device to reconcile
     * @param metadata additional context (e.g., temperature readings)
     * @return the reconciliation result with details
     */
    ReconciliationResult reconcile(DeviceId deviceId, Map<String, Object> metadata);

    /**
     * Reconciles a device using a pre-loaded snapshot.
     * Use this when the snapshot is already available to avoid redundant loading.
     *
     * @param snapshot the pre-loaded device twin snapshot
     * @param system   the FunctionalSystem (may be null for standalone devices)
     * @param metadata additional context
     * @return the reconciliation result with details
     */
    ReconciliationResult reconcile(
            DeviceTwinSnapshot snapshot,
            FunctionalSystemData system,
            Map<String, Object> metadata
    );

    /**
     * Result of a reconciliation operation.
     *
     * @param outcome           the outcome of the reconciliation
     * @param desiredState      the calculated desired state (may be null if no state calculated)
     * @param calculationResult the detailed calculation result from StateCalculator
     * @param reason            human-readable reason for the outcome
     */
    record ReconciliationResult(
            Outcome outcome,
            DesiredDeviceState desiredState,
            StateCalculator.CalculationResult calculationResult,
            String reason
    ) {
        /**
         * Possible outcomes of reconciliation.
         */
        public enum Outcome {
            /**
             * Desired state was calculated and persisted
             */
            SUCCESS,
            /**
             * No desired state could be calculated (no intent)
             */
            NO_CHANGE,
            /**
             * Safety rules refused the change
             */
            SAFETY_REFUSED,
            /**
             * Device snapshot not found
             */
            DEVICE_NOT_FOUND,
            /**
             * Infrastructure is unhealthy (fail-stop)
             */
            INFRASTRUCTURE_UNAVAILABLE,
            /**
             * An error occurred during reconciliation
             */
            ERROR
        }

        public boolean isSuccess() {
            return outcome == Outcome.SUCCESS;
        }

        public boolean hasDesiredState() {
            return desiredState != null;
        }

        public Optional<DesiredDeviceState> getDesiredState() {
            return Optional.ofNullable(desiredState);
        }

        public static ReconciliationResult success(DesiredDeviceState state, StateCalculator.CalculationResult calcResult) {
            return new ReconciliationResult(Outcome.SUCCESS, state, calcResult, "Desired state calculated and persisted");
        }

        public static ReconciliationResult noChange(StateCalculator.CalculationResult calcResult, String reason) {
            return new ReconciliationResult(Outcome.NO_CHANGE, null, calcResult, reason);
        }

        public static ReconciliationResult safetyRefused(StateCalculator.CalculationResult calcResult, String reason) {
            return new ReconciliationResult(Outcome.SAFETY_REFUSED, null, calcResult, reason);
        }

        public static ReconciliationResult deviceNotFound(DeviceId deviceId) {
            return new ReconciliationResult(Outcome.DEVICE_NOT_FOUND, null, null,
                    "Device not found: " + deviceId);
        }

        public static ReconciliationResult infrastructureUnavailable(String reason) {
            return new ReconciliationResult(Outcome.INFRASTRUCTURE_UNAVAILABLE, null, null, reason);
        }

        public static ReconciliationResult error(String reason) {
            return new ReconciliationResult(Outcome.ERROR, null, null, reason);
        }
    }
}

