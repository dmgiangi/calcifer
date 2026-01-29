package dev.dmgiangi.core.server.domain.port;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;

import java.util.Optional;

/**
 * Domain port for resolving effective overrides.
 * This port abstracts the override resolution logic from the application layer,
 * allowing the domain layer (StateCalculator) to access override information
 * without creating circular dependencies.
 *
 * <p>Per Phase 0.5: Override Category Precedence: EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL
 *
 * <p>Conflict Resolution Rules:
 * <ul>
 *   <li>Higher category wins regardless of scope</li>
 *   <li>Same category: DEVICE scope wins (more specific)</li>
 * </ul>
 */
public interface OverrideResolver {

    /**
     * Resolves the effective override for a device.
     * Considers both device-level and system-level overrides.
     *
     * @param deviceId the device ID
     * @param systemId the system ID (may be null for standalone devices)
     * @return Optional containing the resolved override, or empty if no active override
     */
    Optional<ResolvedOverride> resolveEffective(DeviceId deviceId, String systemId);

    /**
     * Resolved override information for state calculation.
     *
     * @param value        the override value (already converted to DeviceValue)
     * @param category     the override category
     * @param reason       the reason for the override
     * @param isFromSystem true if this override comes from a system-level override
     */
    record ResolvedOverride(
            DeviceValue value,
            OverrideCategory category,
            String reason,
            boolean isFromSystem
    ) {
        /**
         * Creates a ResolvedOverride with the given parameters.
         */
        public static ResolvedOverride of(
                final DeviceValue value,
                final OverrideCategory category,
                final String reason,
                final boolean isFromSystem
        ) {
            return new ResolvedOverride(value, category, reason, isFromSystem);
        }
    }
}

