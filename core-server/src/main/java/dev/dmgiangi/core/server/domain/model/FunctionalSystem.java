package dev.dmgiangi.core.server.domain.model;

import java.time.Instant;
import java.util.*;

/**
 * FunctionalSystem Aggregate Root.
 * Per Phase 0.2: Lightweight Aggregate that owns CONFIGURATION and DEVICE MEMBERSHIP only.
 * Device states remain in Redis (via DeviceStateRepository).
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Device auto-registration: devices can exist standalone, be added to systems later</li>
 *   <li>Exclusive membership: a device belongs to at most one system</li>
 *   <li>Optimistic locking via version field</li>
 *   <li>Immutable after construction - modifications return new instances</li>
 * </ul>
 *
 * <p>Invariants:
 * <ul>
 *   <li>ID, type, and name are required and immutable</li>
 *   <li>Configuration is always present (defaults if not specified)</li>
 *   <li>Device IDs set is never null (empty set if no devices)</li>
 * </ul>
 */
public final class FunctionalSystem {

    private final FunctionalSystemId id;
    private final FunctionalSystemType type;
    private final String name;
    private final SystemConfiguration configuration;
    private final Set<DeviceId> deviceIds;
    private final Map<String, Object> failSafeDefaults;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String createdBy;
    private final Long version;

    private FunctionalSystem(
            final FunctionalSystemId id,
            final FunctionalSystemType type,
            final String name,
            final SystemConfiguration configuration,
            final Set<DeviceId> deviceIds,
            final Map<String, Object> failSafeDefaults,
            final Instant createdAt,
            final Instant updatedAt,
            final String createdBy,
            final Long version
    ) {
        this.id = Objects.requireNonNull(id, "FunctionalSystem ID must not be null");
        this.type = Objects.requireNonNull(type, "FunctionalSystem type must not be null");
        this.name = Objects.requireNonNull(name, "FunctionalSystem name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("FunctionalSystem name must not be blank");
        }
        this.configuration = configuration != null ? configuration : SystemConfiguration.ofMode(OperationalMode.OFF);
        this.deviceIds = deviceIds != null ? Set.copyOf(deviceIds) : Set.of();
        this.failSafeDefaults = failSafeDefaults != null ? Map.copyOf(failSafeDefaults) : Map.of();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.createdBy = createdBy;
        this.version = version;
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a new FunctionalSystem with generated ID.
     *
     * @param type      the system type
     * @param name      the human-readable name
     * @param createdBy the creator identifier
     * @return a new FunctionalSystem
     */
    public static FunctionalSystem create(
            final FunctionalSystemType type,
            final String name,
            final String createdBy
    ) {
        return new FunctionalSystem(
                FunctionalSystemId.generate(),
                type,
                name,
                SystemConfiguration.ofMode(OperationalMode.OFF),
                Set.of(),
                Map.of(),
                Instant.now(),
                Instant.now(),
                createdBy,
                null
        );
    }

    /**
     * Creates a new FunctionalSystem with specified ID.
     *
     * @param id        the system ID
     * @param type      the system type
     * @param name      the human-readable name
     * @param createdBy the creator identifier
     * @return a new FunctionalSystem
     */
    public static FunctionalSystem create(
            final FunctionalSystemId id,
            final FunctionalSystemType type,
            final String name,
            final String createdBy
    ) {
        return new FunctionalSystem(
                id,
                type,
                name,
                SystemConfiguration.ofMode(OperationalMode.OFF),
                Set.of(),
                Map.of(),
                Instant.now(),
                Instant.now(),
                createdBy,
                null
        );
    }

    /**
     * Reconstitutes a FunctionalSystem from persistence data.
     * Used by repository adapters to rebuild the aggregate.
     *
     * @param id               the system ID
     * @param type             the system type
     * @param name             the human-readable name
     * @param configuration    the system configuration
     * @param deviceIds        the set of device IDs
     * @param failSafeDefaults the fail-safe defaults
     * @param createdAt        when the system was created
     * @param updatedAt        when the system was last updated
     * @param createdBy        the creator identifier
     * @param version          the optimistic locking version
     * @return a reconstituted FunctionalSystem
     */
    public static FunctionalSystem reconstitute(
            final FunctionalSystemId id,
            final FunctionalSystemType type,
            final String name,
            final SystemConfiguration configuration,
            final Set<DeviceId> deviceIds,
            final Map<String, Object> failSafeDefaults,
            final Instant createdAt,
            final Instant updatedAt,
            final String createdBy,
            final Long version
    ) {
        return new FunctionalSystem(
                id, type, name, configuration, deviceIds, failSafeDefaults,
                createdAt, updatedAt, createdBy, version
        );
    }

    // ==================== Domain Methods ====================

    /**
     * Adds a device to this system.
     * Per Phase 0.2: Supports device auto-registration.
     *
     * @param deviceId the device to add
     * @return a new FunctionalSystem with the device added
     * @throws IllegalArgumentException if device is already in this system
     */
    public FunctionalSystem addDevice(final DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "Device ID must not be null");
        if (deviceIds.contains(deviceId)) {
            throw new IllegalArgumentException(
                    "Device " + deviceId + " is already in system " + id);
        }
        final var newDeviceIds = new HashSet<>(deviceIds);
        newDeviceIds.add(deviceId);
        return new FunctionalSystem(
                id, type, name, configuration, newDeviceIds, failSafeDefaults,
                createdAt, Instant.now(), createdBy, version
        );
    }

    /**
     * Removes a device from this system.
     *
     * @param deviceId the device to remove
     * @return a new FunctionalSystem with the device removed
     * @throws IllegalArgumentException if device is not in this system
     */
    public FunctionalSystem removeDevice(final DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "Device ID must not be null");
        if (!deviceIds.contains(deviceId)) {
            throw new IllegalArgumentException(
                    "Device " + deviceId + " is not in system " + id);
        }
        final var newDeviceIds = new HashSet<>(deviceIds);
        newDeviceIds.remove(deviceId);
        return new FunctionalSystem(
                id, type, name, configuration, newDeviceIds, failSafeDefaults,
                createdAt, Instant.now(), createdBy, version
        );
    }

    /**
     * Updates the system configuration.
     *
     * @param newConfiguration the new configuration
     * @return a new FunctionalSystem with updated configuration
     */
    public FunctionalSystem updateConfiguration(final SystemConfiguration newConfiguration) {
        Objects.requireNonNull(newConfiguration, "Configuration must not be null");
        return new FunctionalSystem(
                id, type, name, newConfiguration, deviceIds, failSafeDefaults,
                createdAt, Instant.now(), createdBy, version
        );
    }

    /**
     * Updates the fail-safe defaults.
     *
     * @param newFailSafeDefaults the new fail-safe defaults
     * @return a new FunctionalSystem with updated fail-safe defaults
     */
    public FunctionalSystem updateFailSafeDefaults(final Map<String, Object> newFailSafeDefaults) {
        return new FunctionalSystem(
                id, type, name, configuration, deviceIds,
                newFailSafeDefaults != null ? newFailSafeDefaults : Map.of(),
                createdAt, Instant.now(), createdBy, version
        );
    }

    /**
     * Changes the operational mode.
     *
     * @param newMode the new operational mode
     * @return a new FunctionalSystem with updated mode
     */
    public FunctionalSystem changeMode(final OperationalMode newMode) {
        Objects.requireNonNull(newMode, "Mode must not be null");
        return updateConfiguration(configuration.withMode(newMode));
    }

    // ==================== Query Methods ====================

    /**
     * Checks if a device belongs to this system.
     *
     * @param deviceId the device to check
     * @return true if the device is in this system
     */
    public boolean containsDevice(final DeviceId deviceId) {
        return deviceId != null && deviceIds.contains(deviceId);
    }

    /**
     * Returns the number of devices in this system.
     *
     * @return the device count
     */
    public int deviceCount() {
        return deviceIds.size();
    }

    /**
     * Checks if this system has any devices.
     *
     * @return true if the system has at least one device
     */
    public boolean hasDevices() {
        return !deviceIds.isEmpty();
    }

    /**
     * Checks if this system type has critical safety interlocks.
     *
     * @return true if the system type requires critical safety rules
     */
    public boolean hasCriticalSafetyInterlocks() {
        return type.hasCriticalSafetyInterlocks();
    }

    /**
     * Checks if the system is currently active (not OFF).
     *
     * @return true if the system is in an active mode
     */
    public boolean isActive() {
        return configuration.mode().isActive();
    }

    /**
     * Checks if the system is in automatic mode.
     *
     * @return true if the system calculates states automatically
     */
    public boolean isAutomatic() {
        return configuration.mode().isAutomatic();
    }

    // ==================== Getters ====================

    public FunctionalSystemId getId() {
        return id;
    }

    public FunctionalSystemType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public SystemConfiguration getConfiguration() {
        return configuration;
    }

    public Set<DeviceId> getDeviceIds() {
        return Collections.unmodifiableSet(deviceIds);
    }

    public Map<String, Object> getFailSafeDefaults() {
        return Collections.unmodifiableMap(failSafeDefaults);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Long getVersion() {
        return version;
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final FunctionalSystem that = (FunctionalSystem) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "FunctionalSystem{" +
                "id=" + id +
                ", type=" + type +
                ", name='" + name + '\'' +
                ", deviceCount=" + deviceIds.size() +
                ", mode=" + configuration.mode() +
                ", version=" + version +
                '}';
    }
}

