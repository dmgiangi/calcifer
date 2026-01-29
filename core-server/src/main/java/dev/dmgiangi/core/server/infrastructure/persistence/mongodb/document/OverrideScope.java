package dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document;

/**
 * Override scope per Phase 0.5.
 *
 * <p>Conflict resolution rules:
 * <ul>
 *   <li>Higher category wins regardless of scope</li>
 *   <li>Same category: DEVICE wins (more specific)</li>
 *   <li>Same category + scope: most recent wins</li>
 * </ul>
 */
public enum OverrideScope {
    /**
     * Override applies to entire FunctionalSystem
     */
    SYSTEM,

    /**
     * Override applies to a specific device
     */
    DEVICE
}

