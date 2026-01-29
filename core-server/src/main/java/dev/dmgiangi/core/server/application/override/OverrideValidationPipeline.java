package dev.dmgiangi.core.server.application.override;

import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;

import java.util.List;
import java.util.Optional;

/**
 * Pipeline for validating and applying override requests.
 * Per Phase 0.5: Validates via SafetyRuleEngine with stacking semantics.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Validate override value against safety rules (HARDCODED_SAFETY/SYSTEM_SAFETY cannot be overridden)</li>
 *   <li>Enforce stacking semantics: one override per (target, category) pair</li>
 *   <li>Resolve conflicts: higher category shadows lower, same category DEVICE wins</li>
 *   <li>Handle expiration: on expiry, next category takes over</li>
 * </ul>
 *
 * <p>Override Category Precedence: EMERGENCY > MAINTENANCE > SCHEDULED > MANUAL
 *
 * <p>Conflict Resolution Rules:
 * <ul>
 *   <li>Higher category wins regardless of scope</li>
 *   <li>Same category: DEVICE wins (more specific)</li>
 *   <li>Same category + scope: most recent wins (replaces existing)</li>
 * </ul>
 */
public interface OverrideValidationPipeline {

    /**
     * Validates and applies an override request.
     *
     * <p>The pipeline:
     * <ol>
     *   <li>Validates the override value against safety rules</li>
     *   <li>Checks for existing override at same (target, category)</li>
     *   <li>Persists the override (replaces existing if same target+category)</li>
     *   <li>Returns the validation result</li>
     * </ol>
     *
     * @param request the override request to validate and apply
     * @return the validation result (Applied, Blocked, or Modified)
     */
    OverrideValidationResult validate(OverrideRequest request);

    /**
     * Validates an override request without persisting.
     * Useful for dry-run validation before applying.
     *
     * @param request the override request to validate
     * @return the validation result (what would happen if applied)
     */
    OverrideValidationResult validateOnly(OverrideRequest request);

    /**
     * Resolves the effective override for a target.
     * Per Phase 0.5: Higher category wins, then DEVICE scope wins.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Find all active overrides for the target (device or system)</li>
     *   <li>If device, also check system-level overrides</li>
     *   <li>Return highest category override</li>
     *   <li>If same category, DEVICE scope wins</li>
     * </ol>
     *
     * @param targetId the target ID (deviceId or systemId)
     * @return Optional containing the effective override, or empty if none active
     */
    Optional<EffectiveOverride> resolveEffective(String targetId);

    /**
     * Resolves the effective override for a device, considering both device and system overrides.
     *
     * @param deviceId the device ID
     * @param systemId the system ID (may be null for standalone devices)
     * @return Optional containing the effective override
     */
    Optional<EffectiveOverride> resolveEffectiveForDevice(String deviceId, String systemId);

    /**
     * Lists all active overrides for a target, ordered by priority.
     *
     * @param targetId the target ID
     * @return list of active overrides, highest priority first
     */
    List<EffectiveOverride> listActiveOverrides(String targetId);

    /**
     * Cancels an override by target and category.
     * On cancellation, the next lower category takes over if exists.
     *
     * @param targetId the target ID
     * @param category the category to cancel
     * @return true if an override was cancelled
     */
    boolean cancelOverride(String targetId, OverrideCategory category);

    /**
     * Represents the effective override for a target after conflict resolution.
     *
     * @param targetId       the target this override applies to
     * @param sourceTargetId the original target of the override (may differ for system-level)
     * @param category       the override category
     * @param value          the override value
     * @param reason         the reason for the override
     * @param isFromSystem   true if this override comes from a system-level override
     * @param shadowedBy     list of higher-priority overrides that shadow this one (for debugging)
     */
    record EffectiveOverride(
            String targetId,
            String sourceTargetId,
            OverrideCategory category,
            Object value,
            String reason,
            boolean isFromSystem,
            List<String> shadowedBy
    ) {
        public EffectiveOverride {
            shadowedBy = shadowedBy != null ? List.copyOf(shadowedBy) : List.of();
        }

        /**
         * Checks if this override is shadowed by a higher-priority override.
         *
         * @return true if shadowed
         */
        public boolean isShadowed() {
            return !shadowedBy.isEmpty();
        }
    }
}

