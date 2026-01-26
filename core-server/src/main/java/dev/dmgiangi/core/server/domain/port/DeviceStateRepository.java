package dev.dmgiangi.core.server.domain.port;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceTwinSnapshot;
import dev.dmgiangi.core.server.domain.model.ReportedDeviceState;
import dev.dmgiangi.core.server.domain.model.UserIntent;
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
}