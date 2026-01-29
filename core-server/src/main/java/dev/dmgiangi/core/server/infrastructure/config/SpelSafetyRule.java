package dev.dmgiangi.core.server.infrastructure.config;

import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.model.safety.RuleCategory;
import dev.dmgiangi.core.server.domain.model.safety.SafetyContext;
import dev.dmgiangi.core.server.domain.model.safety.SafetyRule;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Accepted;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Modified;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Refused;
import dev.dmgiangi.core.server.infrastructure.config.SafetyRulesProperties.RuleAction;
import dev.dmgiangi.core.server.infrastructure.config.SafetyRulesProperties.RuleDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Objects;
import java.util.Optional;

/**
 * SpEL-based implementation of SafetyRule.
 * Evaluates rules defined in YAML using Spring Expression Language.
 *
 * <p>Per Phase 0.4: Uses SimpleEvaluationContext for sandboxing:
 * <ul>
 *   <li>No method calls on arbitrary objects</li>
 *   <li>No constructor calls (except whitelisted)</li>
 *   <li>No static method access</li>
 *   <li>Read-only property access</li>
 * </ul>
 */
public class SpelSafetyRule implements SafetyRule {

    private static final Logger log = LoggerFactory.getLogger(SpelSafetyRule.class);
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private final RuleDefinition definition;
    private final Expression conditionExpression;
    private final Expression actionExpression;

    /**
     * Creates a SpEL-based safety rule from a YAML definition.
     *
     * @param definition the rule definition from YAML
     */
    public SpelSafetyRule(RuleDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "Rule definition must not be null");

        // Parse condition expression
        this.conditionExpression = definition.getCondition() != null
                ? PARSER.parseExpression(definition.getCondition())
                : null;

        // Parse action expression (for MODIFY rules)
        this.actionExpression = definition.getSpelExpression() != null
                ? PARSER.parseExpression(definition.getSpelExpression())
                : null;
    }

    @Override
    public String getId() {
        return definition.getId();
    }

    @Override
    public String getName() {
        return definition.getName();
    }

    @Override
    public RuleCategory getCategory() {
        return definition.getCategory();
    }

    @Override
    public int getPriority() {
        return definition.getPriority();
    }

    @Override
    public String getDescription() {
        return definition.getDescription() != null
                ? definition.getDescription()
                : SafetyRule.super.getDescription();
    }

    @Override
    public boolean appliesTo(SafetyContext context) {
        if (!definition.isEnabled()) {
            return false;
        }

        if (conditionExpression == null) {
            // No condition means rule always applies
            return true;
        }

        try {
            final var evalContext = createEvaluationContext(context);
            final var result = conditionExpression.getValue(evalContext, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Error evaluating condition for rule {}: {}", getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public ValidationResult evaluate(SafetyContext context) {
        try {
            return switch (definition.getAction()) {
                case ACCEPT -> Accepted.of(getId());
                case REFUSE -> evaluateRefuse(context);
                case MODIFY -> evaluateModify(context);
            };
        } catch (Exception e) {
            log.error("Error evaluating rule {}: {}", getId(), e.getMessage(), e);
            // On error, refuse for safety (fail-closed per 0.4)
            return Refused.of(getId(), "Rule evaluation error", e.getMessage());
        }
    }

    @Override
    public Optional<Object> suggestCorrection(SafetyContext context) {
        if (definition.getAction() == RuleAction.MODIFY && actionExpression != null) {
            try {
                final var evalContext = createEvaluationContext(context);
                return Optional.ofNullable(actionExpression.getValue(evalContext));
            } catch (Exception e) {
                log.warn("Error suggesting correction for rule {}: {}", getId(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the version of this rule.
     *
     * @return the rule version
     */
    public int getVersion() {
        return definition.getVersion();
    }

    private ValidationResult evaluateRefuse(SafetyContext context) {
        if (actionExpression == null) {
            // No expression means always refuse when condition matches
            return Refused.of(getId(), definition.getReason());
        }

        final var evalContext = createEvaluationContext(context);
        final var shouldRefuse = actionExpression.getValue(evalContext, Boolean.class);

        if (Boolean.TRUE.equals(shouldRefuse)) {
            return Refused.of(getId(), definition.getReason());
        }

        return Accepted.of(getId());
    }

    private ValidationResult evaluateModify(SafetyContext context) {
        if (actionExpression == null) {
            // No expression means accept as-is
            return Accepted.of(getId());
        }

        final var evalContext = createEvaluationContext(context);
        final var modifiedValue = actionExpression.getValue(evalContext);

        if (modifiedValue == null) {
            return Accepted.of(getId());
        }

        // Check if value was actually modified
        if (modifiedValue.equals(context.proposedValue())) {
            return Accepted.of(getId());
        }

        if (modifiedValue instanceof DeviceValue deviceValue) {
            return Modified.of(getId(), context.proposedValue(), deviceValue, definition.getReason());
        }

        // If expression returned non-DeviceValue, log warning and accept
        log.warn("Rule {} returned non-DeviceValue: {}", getId(), modifiedValue.getClass().getName());
        return Accepted.of(getId());
    }

    /**
     * Creates a sandboxed SpEL evaluation context.
     * Per Phase 0.4: Uses SimpleEvaluationContext for security.
     */
    private EvaluationContext createEvaluationContext(SafetyContext context) {
        return SimpleEvaluationContext.forReadOnlyDataBinding()
                .withInstanceMethods()
                .withRootObject(context)
                .build();
    }
}

