package dev.dmgiangi.core.server.domain.service.rules;

import dev.dmgiangi.core.server.domain.model.safety.RuleCategory;
import dev.dmgiangi.core.server.domain.model.safety.SafetyRule;

/**
 * Base class for hardcoded safety rules.
 * Provides common implementation for HARDCODED_SAFETY category rules.
 *
 * <p>Hardcoded rules are:
 * <ul>
 *   <li>Always evaluated, even when SpEL engine fails</li>
 *   <li>Cannot be overridden by any means</li>
 *   <li>Implemented in Java for maximum reliability</li>
 * </ul>
 */
public abstract class AbstractHardcodedSafetyRule implements SafetyRule {

    private final String id;
    private final String name;
    private final String description;
    private final int priority;

    protected AbstractHardcodedSafetyRule(String id, String name, String description, int priority) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.priority = priority;
    }

    protected AbstractHardcodedSafetyRule(String id, String name, String description) {
        this(id, name, description, 100);
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final RuleCategory getCategory() {
        return RuleCategory.HARDCODED_SAFETY;
    }

    @Override
    public final int getPriority() {
        return priority;
    }

    @Override
    public final String getDescription() {
        return description;
    }
}

