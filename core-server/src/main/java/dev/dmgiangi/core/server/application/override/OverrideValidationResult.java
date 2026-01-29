package dev.dmgiangi.core.server.application.override;

import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideData;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of override validation pipeline.
 * Per Phase 0.5: Three possible outcomes for override requests.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>{@link Applied} - Override was applied successfully</li>
 *   <li>{@link Blocked} - Override was blocked by safety rules</li>
 *   <li>{@link Modified} - Override value was modified to comply with safety rules</li>
 * </ul>
 */
public sealed interface OverrideValidationResult permits
        OverrideValidationResult.Applied,
        OverrideValidationResult.Blocked,
        OverrideValidationResult.Modified {

    /**
     * Checks if the override was applied (either as-is or modified).
     *
     * @return true if the override was applied
     */
    default boolean isApplied() {
        return this instanceof Applied || this instanceof Modified;
    }

    /**
     * Checks if the override was blocked.
     *
     * @return true if blocked
     */
    default boolean isBlocked() {
        return this instanceof Blocked;
    }

    /**
     * Checks if the override value was modified.
     *
     * @return true if modified
     */
    default boolean isModified() {
        return this instanceof Modified;
    }

    /**
     * Returns the applied override data if successful.
     *
     * @return Optional containing the override data
     */
    Optional<OverrideData> getAppliedOverride();

    /**
     * Returns any warnings generated during validation.
     *
     * @return list of warning messages
     */
    List<String> getWarnings();

    /**
     * Override was applied successfully without modifications.
     *
     * @param override the applied override data
     * @param warnings any warnings generated during validation
     */
    record Applied(
            OverrideData override,
            List<String> warnings
    ) implements OverrideValidationResult {
        public Applied {
            Objects.requireNonNull(override, "Override must not be null");
            warnings = warnings != null ? List.copyOf(warnings) : List.of();
        }

        public static Applied of(OverrideData override) {
            return new Applied(override, List.of());
        }

        public static Applied of(OverrideData override, List<String> warnings) {
            return new Applied(override, warnings);
        }

        @Override
        public Optional<OverrideData> getAppliedOverride() {
            return Optional.of(override);
        }

        @Override
        public List<String> getWarnings() {
            return warnings;
        }
    }

    /**
     * Override was blocked by safety rules.
     *
     * @param reason        the reason for blocking
     * @param blockingRules the safety rules that blocked the override
     * @param warnings      any warnings generated during validation
     */
    record Blocked(
            String reason,
            List<String> blockingRules,
            List<String> warnings
    ) implements OverrideValidationResult {
        public Blocked {
            Objects.requireNonNull(reason, "Reason must not be null");
            blockingRules = blockingRules != null ? List.copyOf(blockingRules) : List.of();
            warnings = warnings != null ? List.copyOf(warnings) : List.of();
        }

        public static Blocked of(String reason) {
            return new Blocked(reason, List.of(), List.of());
        }

        public static Blocked of(String reason, List<String> blockingRules) {
            return new Blocked(reason, blockingRules, List.of());
        }

        @Override
        public Optional<OverrideData> getAppliedOverride() {
            return Optional.empty();
        }

        @Override
        public List<String> getWarnings() {
            return warnings;
        }
    }

    /**
     * Override value was modified to comply with safety rules.
     *
     * @param override       the applied override with modified value
     * @param originalValue  the originally requested value
     * @param modifiedValue  the value after safety modification
     * @param modifyingRules the safety rules that modified the value
     * @param warnings       any warnings generated during validation
     */
    record Modified(
            OverrideData override,
            DeviceValue originalValue,
            DeviceValue modifiedValue,
            List<String> modifyingRules,
            List<String> warnings
    ) implements OverrideValidationResult {
        public Modified {
            Objects.requireNonNull(override, "Override must not be null");
            Objects.requireNonNull(originalValue, "Original value must not be null");
            Objects.requireNonNull(modifiedValue, "Modified value must not be null");
            modifyingRules = modifyingRules != null ? List.copyOf(modifyingRules) : List.of();
            warnings = warnings != null ? List.copyOf(warnings) : List.of();
        }

        public static Modified of(OverrideData override, DeviceValue original, DeviceValue modified, List<String> rules) {
            return new Modified(override, original, modified, rules, List.of());
        }

        @Override
        public Optional<OverrideData> getAppliedOverride() {
            return Optional.of(override);
        }

        @Override
        public List<String> getWarnings() {
            return warnings;
        }
    }
}

