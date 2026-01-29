package dev.dmgiangi.core.server.domain.port;

import dev.dmgiangi.core.server.domain.model.*;

import java.util.List;
import java.util.Optional;


public interface DeviceStateRepository {

    // ========== Existing Methods (Desired State) ==========

    void saveDesiredState(DesiredDeviceState state);

    List<DesiredDeviceState> findAllActiveOutputDevices();

    Optional<DesiredDeviceState> findDesiredState(DeviceId id);

    // ========== User Intent Methods ==========

    void saveUserIntent(UserIntent intent);

    Optional<UserIntent> findUserIntent(DeviceId id);

    // ========== Reported State Methods ==========

    void saveReportedState(ReportedDeviceState state);

    Optional<ReportedDeviceState> findReportedState(DeviceId id);

    // ========== Digital Twin Snapshot ==========

    /**
     * Retrieves a complete snapshot of all three states for a device.
     * This is an aggregate read that combines UserIntent, ReportedDeviceState,
     * and DesiredDeviceState into a single DeviceTwinSnapshot.
     *
     * @param id the device identifier
     * @return Optional containing the snapshot if the device exists, empty otherwise
     */
    Optional<DeviceTwinSnapshot> findTwinSnapshot(DeviceId id);

    // ========== Device Lifecycle Methods ==========

    /**
     * Deletes a device and all its associated state (intent, reported, desired).
     * Also removes the device from any indexes.
     * Per Phase 0.15: Used when device is explicitly decommissioned.
     *
     * @param id the device identifier to delete
     */
    void deleteDevice(DeviceId id);

    /**
     * Retrieves the last activity timestamp for a device.
     * Per Phase 0.15: Used for staleness detection.
     *
     * @param id the device identifier
     * @return Optional containing the last activity timestamp, empty if device doesn't exist
     */
    Optional<java.time.Instant> findLastActivity(DeviceId id);
}