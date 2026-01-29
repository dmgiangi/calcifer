package dev.dmgiangi.core.server.domain.service;

import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.model.safety.RuleCategory;
import dev.dmgiangi.core.server.domain.model.safety.SafetyContext;
import dev.dmgiangi.core.server.domain.model.safety.SafetyRule;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Modified;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Refused;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Safety Rules Engine orchestrator.
 * Per Phase 0.4: Evaluates rules by category precedence (HARDCODED_SAFETY > SYSTEM_SAFETY > ... > USER_INTENT).
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Rules are sorted by category (highest first) then by priority (lowest first)</li>
 *   <li>Evaluation stops at first Refused result</li>
 *   <li>Modified results are accumulated and applied in order</li>
 *   <li>Hardcoded rules are always evaluated, even when SpEL engine fails</li>
 * </ul>
 *
 * <p>Thread-safe: rules list is immutable after construction.
 */
@Slf4j
@Service
public class SafetyRuleEngine {

    private final List<SafetyRule> sortedRules;
    private final List<SafetyRule> hardcodedRules;
    private final Counter rulesEvaluatedCounter;
    private final Counter rulesRefusedCounter;
    private final Counter rulesModifiedCounter;
    private final Counter rulesAcceptedCounter;
    private final Timer evaluationTimer;

    /**
     * Constructs the engine with a list of safety rules.
     * Rules are sorted by category precedence (highest first) and priority (lowest first).
     *
     * @param rules         all safety rules (hardcoded + configurable)
     * @param meterRegistry metrics registry for observability
     */
    public SafetyRuleEngine(List<SafetyRule> rules, MeterRegistry meterRegistry) {
        // Sort rules: highest category first, then lowest priority first
        this.sortedRules = rules.stream()
                .sorted(Comparator
                        .comparing(SafetyRule::getCategory, Comparator.reverseOrder())
                        .thenComparing(SafetyRule::getPriority))
                .toList();

        // Extract hardcoded rules for fallback evaluation
        this.hardcodedRules = this.sortedRules.stream()
                .filter(r -> r.getCategory() == RuleCategory.HARDCODED_SAFETY)
                .toList();

        // Initialize metrics
        this.rulesEvaluatedCounter = Counter.builder("calcifer.safety.rules.evaluated")
                .description("Total number of safety rules evaluated")
                .register(meterRegistry);
        this.rulesRefusedCounter = Counter.builder("calcifer.safety.rules.refused")
                .description("Number of changes refused by safety rules")
                .register(meterRegistry);
        this.rulesModifiedCounter = Counter.builder("calcifer.safety.rules.modified")
                .description("Number of changes modified by safety rules")
                .register(meterRegistry);
        this.rulesAcceptedCounter = Counter.builder("calcifer.safety.rules.accepted")
                .description("Number of changes accepted by safety rules")
                .register(meterRegistry);
        this.evaluationTimer = Timer.builder("calcifer.safety.evaluation.duration")
                .description("Time taken to evaluate safety rules")
                .register(meterRegistry);

        log.info("SafetyRuleEngine initialized with {} rules ({} hardcoded)",
                sortedRules.size(), hardcodedRules.size());
    }

    /**
     * Evaluates all applicable safety rules for the given context.
     *
     * @param context the safety evaluation context
     * @return the evaluation result (Accepted, Refused, or Modified)
     */
    public SafetyEvaluationResult evaluate(SafetyContext context) {
        return evaluationTimer.record(() -> doEvaluate(context, sortedRules));
    }

    /**
     * Evaluates only hardcoded safety rules.
     * Used as fallback when SpEL engine fails.
     *
     * @param context the safety evaluation context
     * @return the evaluation result from hardcoded rules only
     */
    public SafetyEvaluationResult evaluateHardcodedOnly(SafetyContext context) {
        log.warn("Evaluating hardcoded rules only (SpEL fallback) for device {}", context.deviceId());
        return evaluationTimer.record(() -> doEvaluate(context, hardcodedRules));
    }

    private SafetyEvaluationResult doEvaluate(SafetyContext context, List<SafetyRule> rulesToEvaluate) {
        final var evaluatedRules = new ArrayList<String>();
        DeviceValue currentValue = context.proposedValue();

        for (final var rule : rulesToEvaluate) {
            // Check if rule applies to this context
            if (!rule.appliesTo(context)) {
                log.trace("Rule {} does not apply to device {}", rule.getId(), context.deviceId());
                continue;
            }

            rulesEvaluatedCounter.increment();
            evaluatedRules.add(rule.getId());

            // Create context with potentially modified value
            final var evalContext = currentValue.equals(context.proposedValue())
                    ? context
                    : SafetyContext.builder()
                    .deviceId(context.deviceId())
                    .deviceType(context.deviceType())
                    .currentSnapshot(context.currentSnapshot())
                    .proposedValue(currentValue)
                    .functionalSystem(context.functionalSystem())
                    .relatedDeviceStates(context.relatedDeviceStates())
                    .metadata(context.metadata())
                    .build();

            final var result = evaluateRule(rule, evalContext);

            if (result instanceof Refused refused) {
                rulesRefusedCounter.increment();
                log.info("Safety rule {} refused change for device {}: {}",
                        rule.getId(), context.deviceId(), refused.reason());
                return SafetyEvaluationResult.refused(refused, evaluatedRules);
            }

            if (result instanceof Modified modified) {
                rulesModifiedCounter.increment();
                log.info("Safety rule {} modified change for device {}: {} -> {}",
                        rule.getId(), context.deviceId(),
                        modified.originalValue(), modified.modifiedValue());
                currentValue = modified.modifiedValue();
            }
        }

        // All rules passed
        rulesAcceptedCounter.increment();

        if (currentValue.equals(context.proposedValue())) {
            log.debug("All safety rules accepted change for device {}", context.deviceId());
            return SafetyEvaluationResult.accepted(evaluatedRules);
        } else {
            log.debug("Safety rules modified change for device {}: {} -> {}",
                    context.deviceId(), context.proposedValue(), currentValue);
            return SafetyEvaluationResult.modified(context.proposedValue(), currentValue, evaluatedRules);
        }
    }

    private ValidationResult evaluateRule(SafetyRule rule, SafetyContext context) {
        try {
            return rule.evaluate(context);
        } catch (Exception e) {
            log.error("Error evaluating safety rule {}: {}", rule.getId(), e.getMessage(), e);
            // Fail-safe: treat evaluation error as refusal for safety
            return Refused.of(rule.getId(),
                    "Rule evaluation failed: " + e.getMessage(),
                    "Exception: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Returns the number of registered rules.
     *
     * @return total rule count
     */
    public int getRuleCount() {
        return sortedRules.size();
    }

    /**
     * Returns the number of hardcoded rules.
     *
     * @return hardcoded rule count
     */
    public int getHardcodedRuleCount() {
        return hardcodedRules.size();
    }

    /**
     * Finds a rule by ID.
     *
     * @param ruleId the rule identifier
     * @return Optional containing the rule if found
     */
    public Optional<SafetyRule> findRuleById(String ruleId) {
        return sortedRules.stream()
                .filter(r -> r.getId().equals(ruleId))
                .findFirst();
    }
}

