package dev.dmgiangi.core.server.domain.exception;

/**
 * Exception thrown when an operation violates a safety rule.
 * Per Phase 0.4: HARDCODED_SAFETY and SYSTEM_SAFETY rules cannot be overridden.
 */
public class SafetyRuleViolationException extends RuntimeException {

    private final String ruleId;
    private final String ruleName;
    private final String deviceId;

    public SafetyRuleViolationException(final String ruleId, final String ruleName, final String deviceId, final String detail) {
        super(String.format("Safety rule '%s' (%s) violated for device %s: %s", ruleName, ruleId, deviceId, detail));
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.deviceId = deviceId;
    }

    public SafetyRuleViolationException(final String ruleName, final String detail) {
        super(String.format("Safety rule '%s' violated: %s", ruleName, detail));
        this.ruleId = null;
        this.ruleName = ruleName;
        this.deviceId = null;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getDeviceId() {
        return deviceId;
    }
}

