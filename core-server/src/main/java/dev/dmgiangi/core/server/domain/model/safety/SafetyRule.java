package dev.dmgiangi.core.server.domain.model.safety;

import java.util.Optional;

/**
 * Interface for safety rules in the Safety Rules Engine.
 * Per Phase 0.4: Rules are organized by category with fixed precedence.
 *
 * <p>Safety rules validate proposed device state changes and can:
 * <ul>
 *   <li>Accept the change as-is</li>
 *   <li>Refuse the change with a reason</li>
 *   <li>Modify the change to a safe value</li>
 * </ul>
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>Rules should be stateless and thread-safe</li>
 *   <li>Rules should not throw exceptions during evaluation</li>
 *   <li>Rules should return quickly (no blocking I/O)</li>
 * </ul>
 *
 * @see RuleCategory for precedence ordering
 * @see SafetyContext for evaluation context
 * @see ValidationResult for evaluation outcomes
 */
public interface SafetyRule {

    /**
     * Returns the unique identifier for this rule.
     * Used for logging, auditing, and error reporting.
     *
     * @return the rule identifier (e.g., "PUMP_FIRE_INTERLOCK", "MAX_FAN_SPEED")
     */
    String getId();

    /**
     * Returns a human-readable name for this rule.
     * Used in error messages and UI display.
     *
     * @return the rule name (e.g., "Pump-Fire Interlock Safety Rule")
     */
    String getName();

    /**
     * Returns the category of this rule.
     * Category determines precedence in conflict resolution.
     *
     * @return the rule category
     */
    RuleCategory getCategory();

    /**
     * Returns the priority within the category.
     * Lower values = higher priority (evaluated first).
     * Default priority is 100.
     *
     * @return the priority value (lower = higher priority)
     */
    default int getPriority() {
        return 100;
    }

    /**
     * Checks if this rule applies to the given context.
     * Called before evaluate() to skip irrelevant rules.
     *
     * <p>Examples of when a rule might not apply:
     * <ul>
     *   <li>Rule is for RELAY devices but context is for FAN</li>
     *   <li>Rule is for a specific FunctionalSystem but device is standalone</li>
     *   <li>Rule requires temperature data but none is available</li>
     * </ul>
     *
     * @param context the safety evaluation context
     * @return true if this rule should be evaluated for the given context
     */
    boolean appliesTo(SafetyContext context);

    /**
     * Evaluates the rule against the given context.
     * Only called if appliesTo() returns true.
     *
     * <p>The evaluation should:
     * <ul>
     *   <li>Return {@link ValidationResult.Accepted} if the proposed change is safe</li>
     *   <li>Return {@link ValidationResult.Refused} if the change violates safety</li>
     *   <li>Return {@link ValidationResult.Modified} if the change can be made safe with modifications</li>
     * </ul>
     *
     * @param context the safety evaluation context
     * @return the validation result
     */
    ValidationResult evaluate(SafetyContext context);

    /**
     * Suggests a safe correction for a refused change.
     * Called when evaluate() returns Refused to provide a safe alternative.
     *
     * <p>This is optional - rules may return empty if no safe alternative exists.
     *
     * @param context the safety evaluation context
     * @return an optional safe value, or empty if no correction is possible
     */
    default Optional<Object> suggestCorrection(SafetyContext context) {
        return Optional.empty();
    }

    /**
     * Returns a description of what this rule enforces.
     * Used for documentation and operator guidance.
     *
     * @return the rule description
     */
    default String getDescription() {
        return "Safety rule: " + getName();
    }
}

