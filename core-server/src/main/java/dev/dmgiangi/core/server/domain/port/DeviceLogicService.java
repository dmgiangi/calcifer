package dev.dmgiangi.core.server.domain.port;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;

/**
 * Domain service responsible for calculating the desired state of a device
 * based on the Digital Twin pattern: UserIntent + ReportedState + Rules = DesiredState.
 *
 * <p>This service is the central point for state calculation logic. It:
 * <ul>
 *   <li>Reacts to {@code UserIntentChangedEvent} and {@code ReportedStateChangedEvent}</li>
 *   <li>Loads the complete device twin snapshot from the repository</li>
 *   <li>Applies business rules to calculate the new desired state</li>
 *   <li>Persists the result and publishes {@code DesiredStateCalculatedEvent}</li>
 * </ul>
 *
 * <p>Initial implementation uses passthrough logic (Desired = Intent).
 * Future versions will incorporate reported state and business rules.
 */
public interface DeviceLogicService {

    /**
     * Recalculates the desired state for a device.
     * This method orchestrates the full flow:
     * <ol>
     *   <li>Load DeviceTwinSnapshot from repository</li>
     *   <li>Calculate new DesiredDeviceState using {@link #calculateDesired(DeviceTwinSnapshot)}</li>
     *   <li>Save the new desired state to repository</li>
     *   <li>Publish DesiredStateCalculatedEvent</li>
     * </ol>
     *
     * @param id the device identifier to recalculate
     */
    void recalculateDesiredState(DeviceId id);

    /**
     * Pure calculation function: computes the desired state from a twin snapshot.
     * This method contains the business logic and is easily testable in isolation.
     *
     * <p>Current implementation (Phase 1): Passthrough - returns Intent as Desired.
     * <p>Future implementation (Phase 4): Will incorporate ReportedState and business rules.
     *
     * @param snapshot the complete device twin snapshot
     * @return the calculated desired state, or null if no intent exists
     */
    DesiredDeviceState calculateDesired(DeviceTwinSnapshot snapshot);
}

