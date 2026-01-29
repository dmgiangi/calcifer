package dev.dmgiangi.core.server.infrastructure.config;

import dev.dmgiangi.core.server.domain.model.safety.RuleCategory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for safety rules loaded from YAML.
 * Per Phase 0.4: Configurable rules with SpEL conditions.
 *
 * <p>Hot-reload can be achieved via Spring Boot Actuator's /actuator/refresh endpoint
 * when Spring Cloud Config is added, or by restarting the application.
 */
@ConfigurationProperties(prefix = "safety")
public class SafetyRulesProperties {

    private List<RuleDefinition> rules = new ArrayList<>();
    private Settings settings = new Settings();

    public List<RuleDefinition> getRules() {
        return rules;
    }

    public void setRules(List<RuleDefinition> rules) {
        this.rules = rules;
    }

    public Settings getSettings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    /**
     * Definition of a configurable safety rule.
     */
    public static class RuleDefinition {
        private String id;
        private String name;
        private String description;
        private RuleCategory category = RuleCategory.SYSTEM_SAFETY;
        private int priority = 100;
        private boolean enabled = true;
        private int version = 1;
        private String condition;
        private RuleAction action = RuleAction.REFUSE;
        private String spelExpression;
        private String reason;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public RuleCategory getCategory() {
            return category;
        }

        public void setCategory(RuleCategory category) {
            this.category = category;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public RuleAction getAction() {
            return action;
        }

        public void setAction(RuleAction action) {
            this.action = action;
        }

        public String getSpelExpression() {
            return spelExpression;
        }

        public void setSpelExpression(String spelExpression) {
            this.spelExpression = spelExpression;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /**
     * Rule action types.
     */
    public enum RuleAction {
        /**
         * Accept the change as-is
         */
        ACCEPT,
        /**
         * Refuse the change
         */
        REFUSE,
        /**
         * Modify the change to a safe value
         */
        MODIFY
    }

    /**
     * Global settings for safety rule evaluation.
     */
    public static class Settings {
        private int evaluationTimeoutMs = 100;
        private boolean failOpen = false;
        private String logLevel = "DEBUG";

        public int getEvaluationTimeoutMs() {
            return evaluationTimeoutMs;
        }

        public void setEvaluationTimeoutMs(int evaluationTimeoutMs) {
            this.evaluationTimeoutMs = evaluationTimeoutMs;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public String getLogLevel() {
            return logLevel;
        }

        public void setLogLevel(String logLevel) {
            this.logLevel = logLevel;
        }
    }
}

