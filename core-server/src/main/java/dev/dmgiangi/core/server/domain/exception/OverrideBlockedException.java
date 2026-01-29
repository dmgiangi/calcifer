package dev.dmgiangi.core.server.domain.exception;

import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;

/**
 * Exception thrown when an override is blocked by a higher priority override or safety rule.
 * Per Phase 0.5: Category-based conflict resolution - higher category wins.
 */
public class OverrideBlockedException extends RuntimeException {

    private final String targetId;
    private final OverrideCategory requestedCategory;
    private final OverrideCategory blockingCategory;
    private final String blockingReason;

    public OverrideBlockedException(
            final String targetId,
            final OverrideCategory requestedCategory,
            final OverrideCategory blockingCategory,
            final String blockingReason
    ) {
        super(String.format(
                "Override blocked for target %s: requested category %s blocked by %s (%s)",
                targetId, requestedCategory, blockingCategory, blockingReason
        ));
        this.targetId = targetId;
        this.requestedCategory = requestedCategory;
        this.blockingCategory = blockingCategory;
        this.blockingReason = blockingReason;
    }

    public String getTargetId() {
        return targetId;
    }

    public OverrideCategory getRequestedCategory() {
        return requestedCategory;
    }

    public OverrideCategory getBlockingCategory() {
        return blockingCategory;
    }

    public String getBlockingReason() {
        return blockingReason;
    }
}

