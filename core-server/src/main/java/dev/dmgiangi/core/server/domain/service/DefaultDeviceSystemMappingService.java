package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.FunctionalSystemId;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository;
import dev.dmgiangi.core.server.domain.port.FunctionalSystemRepository.FunctionalSystemData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link DeviceSystemMappingService}.
 * Provides bidirectional lookup between devices and FunctionalSystems.
 *
 * <p>Per Phase 0.2: Exclusive membership - a device belongs to at most one system.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDeviceSystemMappingService implements DeviceSystemMappingService {

    private final FunctionalSystemRepository functionalSystemRepository;

    @Override
    public Optional<FunctionalSystemData> findSystemByDevice(final DeviceId deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        final var deviceIdString = deviceId.controllerId() + ":" + deviceId.componentId();
        return functionalSystemRepository.findByDeviceId(deviceIdString);
    }

    @Override
    public Optional<FunctionalSystemId> findSystemIdByDevice(final DeviceId deviceId) {
        return findSystemByDevice(deviceId)
                .map(system -> FunctionalSystemId.fromString(system.id()));
    }

    @Override
    public Set<DeviceId> findDevicesBySystem(final FunctionalSystemId systemId) {
        if (systemId == null) {
            return Set.of();
        }
        return functionalSystemRepository.findById(systemId.toString())
                .map(system -> system.deviceIds().stream()
                        .map(this::parseDeviceId)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    @Override
    public boolean isDeviceInSystem(final DeviceId deviceId) {
        return findSystemByDevice(deviceId).isPresent();
    }

    @Override
    public boolean isDeviceInSystem(final DeviceId deviceId, final FunctionalSystemId systemId) {
        if (deviceId == null || systemId == null) {
            return false;
        }
        return findSystemByDevice(deviceId)
                .map(system -> system.id().equals(systemId.toString()))
                .orElse(false);
    }

    @Override
    public Set<DeviceId> findRelatedDevices(final DeviceId deviceId) {
        if (deviceId == null) {
            return Set.of();
        }
        final var systemOpt = findSystemByDevice(deviceId);
        if (systemOpt.isEmpty()) {
            return Set.of();
        }

        final var relatedDevices = new HashSet<DeviceId>();
        for (final var deviceIdString : systemOpt.get().deviceIds()) {
            parseDeviceId(deviceIdString)
                    .filter(id -> !id.equals(deviceId))
                    .ifPresent(relatedDevices::add);
        }
        return relatedDevices;
    }

    @Override
    public Set<DeviceId> findStandaloneDevices(final Set<DeviceId> allKnownDeviceIds) {
        if (allKnownDeviceIds == null || allKnownDeviceIds.isEmpty()) {
            return Set.of();
        }

        // Get all device IDs that are assigned to any system
        final Set<DeviceId> assignedDeviceIds = functionalSystemRepository.findAll().stream()
                .flatMap(system -> system.deviceIds().stream())
                .map(this::parseDeviceId)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());

        // Return devices that are not assigned
        return allKnownDeviceIds.stream()
                .filter(deviceId -> !assignedDeviceIds.contains(deviceId))
                .collect(Collectors.toSet());
    }

    @Override
    public MembershipValidation validateMembership(final DeviceId deviceId, final FunctionalSystemId targetSystemId) {
        if (deviceId == null || targetSystemId == null) {
            return MembershipValidation.validResult();
        }

        final var currentSystemOpt = findSystemByDevice(deviceId);
        if (currentSystemOpt.isEmpty()) {
            // Device is standalone, can be added to any system
            return MembershipValidation.validResult();
        }

        final var currentSystem = currentSystemOpt.get();
        final var currentSystemId = FunctionalSystemId.fromString(currentSystem.id());

        if (currentSystemId.equals(targetSystemId)) {
            // Device is already in the target system
            return MembershipValidation.alreadyInTargetSystem();
        }

        // Device is in a different system - exclusive membership violation
        log.debug("Device {} is already in system {}, cannot add to system {}",
                deviceId, currentSystemId, targetSystemId);
        return MembershipValidation.inOtherSystem(currentSystemId);
    }

    /**
     * Parses a device ID string (format: "controllerId:componentId") into a DeviceId.
     */
    private Optional<DeviceId> parseDeviceId(final String deviceIdString) {
        if (deviceIdString == null || deviceIdString.isBlank()) {
            return Optional.empty();
        }
        final var parts = deviceIdString.split(":", 2);
        if (parts.length != 2) {
            log.warn("Invalid device ID format: {}", deviceIdString);
            return Optional.empty();
        }
        return Optional.of(new DeviceId(parts[0], parts[1]));
    }
}

