package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Refused;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of safety rule evaluation by SafetyRuleEngine.
 * Aggregates the outcome of evaluating multiple rules.
 *
 * <p>Three possible outcomes:
 * <ul>
 *   <li>ACCEPTED - All rules passed, proposed value is safe</li>
 *   <li>REFUSED - A rule blocked the change</li>
 *   <li>MODIFIED - Rules modified the value to be safe</li>
 * </ul>
 */
public record SafetyEvaluationResult(
        Outcome outcome,
        DeviceValue originalValue,
        DeviceValue finalValue,
        Refused refusalDetails,
        List<String> evaluatedRules
) {
    /**
     * Evaluation outcome types.
     */
    public enum Outcome {
        ACCEPTED,
        REFUSED,
        MODIFIED
    }

    public SafetyEvaluationResult {
        Objects.requireNonNull(outcome, "Outcome must not be null");
        Objects.requireNonNull(evaluatedRules, "Evaluated rules list must not be null");
        evaluatedRules = List.copyOf(evaluatedRules);
    }

    /**
     * Creates an accepted result.
     *
     * @param evaluatedRules list of rule IDs that were evaluated
     * @return an accepted result
     */
    public static SafetyEvaluationResult accepted(List<String> evaluatedRules) {
        return new SafetyEvaluationResult(
                Outcome.ACCEPTED,
                null,
                null,
                null,
                evaluatedRules
        );
    }

    /**
     * Creates a refused result.
     *
     * @param refused        the refusal details from the blocking rule
     * @param evaluatedRules list of rule IDs that were evaluated
     * @return a refused result
     */
    public static SafetyEvaluationResult refused(Refused refused, List<String> evaluatedRules) {
        return new SafetyEvaluationResult(
                Outcome.REFUSED,
                null,
                null,
                refused,
                evaluatedRules
        );
    }

    /**
     * Creates a modified result.
     *
     * @param originalValue  the originally proposed value
     * @param finalValue     the modified safe value
     * @param evaluatedRules list of rule IDs that were evaluated
     * @return a modified result
     */
    public static SafetyEvaluationResult modified(
            DeviceValue originalValue,
            DeviceValue finalValue,
            List<String> evaluatedRules
    ) {
        return new SafetyEvaluationResult(
                Outcome.MODIFIED,
                originalValue,
                finalValue,
                null,
                evaluatedRules
        );
    }

    /**
     * Checks if the evaluation was accepted (no modifications).
     *
     * @return true if accepted
     */
    public boolean isAccepted() {
        return outcome == Outcome.ACCEPTED;
    }

    /**
     * Checks if the evaluation was refused.
     *
     * @return true if refused
     */
    public boolean isRefused() {
        return outcome == Outcome.REFUSED;
    }

    /**
     * Checks if the value was modified.
     *
     * @return true if modified
     */
    public boolean isModified() {
        return outcome == Outcome.MODIFIED;
    }

    /**
     * Checks if the change is allowed (accepted or modified).
     *
     * @return true if the change can proceed
     */
    public boolean isAllowed() {
        return outcome != Outcome.REFUSED;
    }

    /**
     * Returns the refusal reason if refused.
     *
     * @return Optional containing the reason, or empty if not refused
     */
    public Optional<String> getRefusalReason() {
        return Optional.ofNullable(refusalDetails).map(Refused::reason);
    }

    /**
     * Returns the blocking rule ID if refused.
     *
     * @return Optional containing the rule ID, or empty if not refused
     */
    public Optional<String> getBlockingRuleId() {
        return Optional.ofNullable(refusalDetails).map(Refused::ruleId);
    }

    /**
     * Returns the value to use (finalValue if modified, null otherwise).
     * Caller should use original proposed value if this returns empty.
     *
     * @return Optional containing the final value if modified
     */
    public Optional<DeviceValue> getEffectiveValue() {
        return Optional.ofNullable(finalValue);
    }
}

