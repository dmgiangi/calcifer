package dev.dmgiangi.core.server.infrastructure.config;

import dev.dmgiangi.core.server.domain.model.safety.RuleCategory;
import dev.dmgiangi.core.server.domain.model.safety.SafetyRule;
import dev.dmgiangi.core.server.infrastructure.config.SafetyRulesProperties.RuleDefinition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Loads safety rules from YAML configuration and converts them to SafetyRule instances.
 * Per Phase 0.4: Supports versioning and runtime reload.
 *
 * <p>Features:
 * <ul>
 *   <li>Loads rules from SafetyRulesProperties (YAML-backed)</li>
 *   <li>Validates rule definitions at load time</li>
 *   <li>Tracks rule versions for audit</li>
 *   <li>Provides metrics for loaded rules</li>
 * </ul>
 */
@Component
public class YamlRuleLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlRuleLoader.class);

    private final SafetyRulesProperties properties;
    private final MeterRegistry meterRegistry;
    private final Counter rulesLoadedCounter;
    private final Counter rulesFailedCounter;

    private final Map<String, SpelSafetyRule> loadedRules = new ConcurrentHashMap<>();
    private volatile List<SafetyRule> cachedRuleList = Collections.emptyList();

    public YamlRuleLoader(SafetyRulesProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.rulesLoadedCounter = Counter.builder("calcifer.safety.rules.loaded")
                .description("Number of safety rules successfully loaded from YAML")
                .register(meterRegistry);
        this.rulesFailedCounter = Counter.builder("calcifer.safety.rules.failed")
                .description("Number of safety rules that failed to load")
                .register(meterRegistry);
    }

    /**
     * Loads rules from YAML configuration on startup.
     */
    @PostConstruct
    public void loadRules() {
        log.info("Loading safety rules from YAML configuration...");

        final var definitions = properties.getRules();
        if (definitions == null || definitions.isEmpty()) {
            log.info("No safety rules defined in YAML configuration");
            return;
        }

        final var newRules = new ConcurrentHashMap<String, SpelSafetyRule>();
        int loaded = 0;
        int failed = 0;

        for (final var definition : definitions) {
            try {
                validateDefinition(definition);
                final var rule = new SpelSafetyRule(definition);
                newRules.put(rule.getId(), rule);
                loaded++;
                rulesLoadedCounter.increment();
                log.debug("Loaded rule: {} (category={}, priority={}, version={}, enabled={})",
                        rule.getId(), rule.getCategory(), rule.getPriority(),
                        rule.getVersion(), definition.isEnabled());
            } catch (Exception e) {
                failed++;
                rulesFailedCounter.increment();
                log.error("Failed to load rule '{}': {}", definition.getId(), e.getMessage(), e);
            }
        }

        // Atomic swap of loaded rules
        loadedRules.clear();
        loadedRules.putAll(newRules);
        cachedRuleList = List.copyOf(loadedRules.values());

        log.info("Loaded {} safety rules from YAML ({} failed)", loaded, failed);
    }

    /**
     * Returns all loaded YAML-based safety rules.
     * Does not include hardcoded rules.
     *
     * @return immutable list of loaded rules
     */
    public List<SafetyRule> getLoadedRules() {
        return cachedRuleList;
    }

    /**
     * Returns rules filtered by category.
     *
     * @param category the category to filter by
     * @return list of rules in the specified category
     */
    public List<SafetyRule> getRulesByCategory(RuleCategory category) {
        return cachedRuleList.stream()
                .filter(rule -> rule.getCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * Returns a specific rule by ID.
     *
     * @param ruleId the rule ID
     * @return the rule, or null if not found
     */
    public SafetyRule getRule(String ruleId) {
        return loadedRules.get(ruleId);
    }

    /**
     * Returns the global settings from YAML configuration.
     *
     * @return the settings
     */
    public SafetyRulesProperties.Settings getSettings() {
        return properties.getSettings();
    }

    /**
     * Reloads rules from YAML configuration.
     * Can be called to refresh rules at runtime.
     */
    public void reload() {
        log.info("Reloading safety rules from YAML configuration...");
        loadRules();
    }

    /**
     * Returns the number of loaded rules.
     *
     * @return count of loaded rules
     */
    public int getRuleCount() {
        return loadedRules.size();
    }

    private void validateDefinition(RuleDefinition definition) {
        if (definition.getId() == null || definition.getId().isBlank()) {
            throw new IllegalArgumentException("Rule ID is required");
        }
        if (definition.getName() == null || definition.getName().isBlank()) {
            throw new IllegalArgumentException("Rule name is required for rule: " + definition.getId());
        }
        if (definition.getCategory() == null) {
            throw new IllegalArgumentException("Rule category is required for rule: " + definition.getId());
        }
        // HARDCODED_SAFETY rules should not be defined in YAML
        if (definition.getCategory() == RuleCategory.HARDCODED_SAFETY) {
            throw new IllegalArgumentException(
                    "HARDCODED_SAFETY rules cannot be defined in YAML: " + definition.getId());
        }
    }
}

