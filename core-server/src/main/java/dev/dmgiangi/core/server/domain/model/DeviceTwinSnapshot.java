package dev.dmgiangi.core.server.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Aggregates the three states of a device in the Digital Twin pattern.
 * Provides a complete snapshot of:
 * <ul>
 *   <li><b>Intent</b>: What the user wants (UserIntent)</li>
 *   <li><b>Reported</b>: What the device says it is (ReportedDeviceState)</li>
 *   <li><b>Desired</b>: The calculated target state (DesiredDeviceState)</li>
 * </ul>
 *
 * <p>All three states are optional because:
 * <ul>
 *   <li>Intent may be null if user never set a preference</li>
 *   <li>Reported may be null if device never sent feedback</li>
 *   <li>Desired may be null if logic hasn't computed yet</li>
 * </ul>
 *
 * @param id       the device identifier
 * @param type     the device type (RELAY or FAN)
 * @param intent   the user's intent (nullable)
 * @param reported the device's reported state (nullable)
 * @param desired  the calculated desired state (nullable)
 */
public record DeviceTwinSnapshot(
    DeviceId id,
    DeviceType type,
    UserIntent intent,
    ReportedDeviceState reported,
    DesiredDeviceState desired
) {
    public DeviceTwinSnapshot {
        Objects.requireNonNull(id, "Device id must not be null");
        Objects.requireNonNull(type, "Device type must not be null");

        // Validate type consistency across all non-null states
        if (intent != null && intent.type() != type) {
            throw new IllegalArgumentException("Intent type must match snapshot type");
        }
        if (reported != null && reported.type() != type) {
            throw new IllegalArgumentException("Reported state type must match snapshot type");
        }
        if (desired != null && desired.type() != type) {
            throw new IllegalArgumentException("Desired state type must match snapshot type");
        }
    }

    /**
     * @return the user intent as Optional
     */
    public Optional<UserIntent> getIntent() {
        return Optional.ofNullable(intent);
    }

    /**
     * @return the reported state as Optional
     */
    public Optional<ReportedDeviceState> getReported() {
        return Optional.ofNullable(reported);
    }

    /**
     * @return the desired state as Optional
     */
    public Optional<DesiredDeviceState> getDesired() {
        return Optional.ofNullable(desired);
    }

    /**
     * Checks if the device state is converged (reported matches desired).
     *
     * @return true if both reported and desired are present and their values match
     */
    public boolean isConverged() {
        if (reported == null || desired == null) {
            return false;
        }
        if (!reported.isKnown()) {
            return false;
        }
        return Objects.equals(reported.value(), desired.value());
    }

    /**
     * Factory method to create an empty snapshot (no states known yet).
     *
     * @param id   the device identifier
     * @param type the device type
     * @return an empty DeviceTwinSnapshot
     */
    public static DeviceTwinSnapshot empty(DeviceId id, DeviceType type) {
        return new DeviceTwinSnapshot(id, type, null, null, null);
    }
}

