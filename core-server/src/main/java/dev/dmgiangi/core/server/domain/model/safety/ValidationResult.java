package dev.dmgiangi.core.server.domain.model.safety;

import dev.dmgiangi.core.server.domain.model.DeviceValue;

import java.util.Objects;

/**
 * Sealed interface representing the result of a safety rule evaluation.
 * Per Phase 0.4: Rules can accept, refuse, or modify proposed changes.
 *
 * <p>Three possible outcomes:
 * <ul>
 *   <li>{@link Accepted} - The proposed change is safe and allowed</li>
 *   <li>{@link Refused} - The proposed change violates safety and is blocked</li>
 *   <li>{@link Modified} - The proposed change was modified to be safe</li>
 * </ul>
 */
public sealed interface ValidationResult permits
        ValidationResult.Accepted,
        ValidationResult.Refused,
        ValidationResult.Modified {

    /**
     * Returns the rule ID that produced this result.
     *
     * @return the rule identifier
     */
    String ruleId();

    /**
     * Checks if the validation passed (accepted or modified).
     *
     * @return true if the change is allowed (possibly modified)
     */
    default boolean isAllowed() {
        return this instanceof Accepted || this instanceof Modified;
    }

    /**
     * Checks if the validation was refused.
     *
     * @return true if the change was blocked
     */
    default boolean isRefused() {
        return this instanceof Refused;
    }

    /**
     * Checks if the value was modified.
     *
     * @return true if the change was modified to be safe
     */
    default boolean isModified() {
        return this instanceof Modified;
    }

    /**
     * The proposed change is safe and allowed as-is.
     *
     * @param ruleId the rule that accepted the change
     */
    record Accepted(String ruleId) implements ValidationResult {
        public Accepted {
            Objects.requireNonNull(ruleId, "Rule ID must not be null");
        }

        /**
         * Factory method for creating an accepted result.
         *
         * @param ruleId the rule that accepted the change
         * @return an Accepted result
         */
        public static Accepted of(String ruleId) {
            return new Accepted(ruleId);
        }
    }

    /**
     * The proposed change violates safety and is blocked.
     *
     * @param ruleId  the rule that refused the change
     * @param reason  human-readable explanation of why the change was refused
     * @param details optional technical details for debugging
     */
    record Refused(String ruleId, String reason, String details) implements ValidationResult {
        public Refused {
            Objects.requireNonNull(ruleId, "Rule ID must not be null");
            Objects.requireNonNull(reason, "Reason must not be null");
        }

        /**
         * Factory method for creating a refused result without details.
         *
         * @param ruleId the rule that refused the change
         * @param reason the reason for refusal
         * @return a Refused result
         */
        public static Refused of(String ruleId, String reason) {
            return new Refused(ruleId, reason, null);
        }

        /**
         * Factory method for creating a refused result with details.
         *
         * @param ruleId  the rule that refused the change
         * @param reason  the reason for refusal
         * @param details technical details
         * @return a Refused result
         */
        public static Refused of(String ruleId, String reason, String details) {
            return new Refused(ruleId, reason, details);
        }
    }

    /**
     * The proposed change was modified to be safe.
     *
     * @param ruleId        the rule that modified the change
     * @param originalValue the originally proposed value
     * @param modifiedValue the safe value that will be used instead
     * @param reason        explanation of why the modification was needed
     */
    record Modified(
            String ruleId,
            DeviceValue originalValue,
            DeviceValue modifiedValue,
            String reason
    ) implements ValidationResult {
        public Modified {
            Objects.requireNonNull(ruleId, "Rule ID must not be null");
            Objects.requireNonNull(originalValue, "Original value must not be null");
            Objects.requireNonNull(modifiedValue, "Modified value must not be null");
            Objects.requireNonNull(reason, "Reason must not be null");
        }

        /**
         * Factory method for creating a modified result.
         *
         * @param ruleId        the rule that modified the change
         * @param originalValue the originally proposed value
         * @param modifiedValue the safe value
         * @param reason        the reason for modification
         * @return a Modified result
         */
        public static Modified of(String ruleId, DeviceValue originalValue,
                                  DeviceValue modifiedValue, String reason) {
            return new Modified(ruleId, originalValue, modifiedValue, reason);
        }
    }
}

