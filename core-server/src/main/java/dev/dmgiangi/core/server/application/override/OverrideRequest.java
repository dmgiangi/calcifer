package dev.dmgiangi.core.server.application.override;

import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Request to apply an override to a device or system.
 * Per Phase 0.5: Categorized overrides with stacking semantics.
 *
 * <p>Key validation rules:
 * <ul>
 *   <li>HARDCODED_SAFETY and SYSTEM_SAFETY cannot be overridden (enforced by SafetyRuleEngine)</li>
 *   <li>One override per (target, category) pair</li>
 *   <li>Higher category shadows lower</li>
 *   <li>Same category: DEVICE wins (more specific)</li>
 * </ul>
 *
 * @param targetId  the target identifier (deviceId or systemId)
 * @param scope     the override scope (SYSTEM or DEVICE)
 * @param category  the override category (MANUAL, SCHEDULED, MAINTENANCE, EMERGENCY)
 * @param value     the override value (DeviceValue for type safety)
 * @param reason    the reason for the override (required for audit)
 * @param expiresAt optional expiration time (null = permanent until cancelled)
 * @param createdBy the user/system that created the override
 */
public record OverrideRequest(
        String targetId,
        OverrideScope scope,
        OverrideCategory category,
        DeviceValue value,
        String reason,
        Instant expiresAt,
        String createdBy
) {
    public OverrideRequest {
        Objects.requireNonNull(targetId, "Target ID must not be null");
        Objects.requireNonNull(scope, "Scope must not be null");
        Objects.requireNonNull(category, "Category must not be null");
        Objects.requireNonNull(value, "Value must not be null");
        Objects.requireNonNull(reason, "Reason must not be null");
        Objects.requireNonNull(createdBy, "CreatedBy must not be null");

        if (targetId.isBlank()) {
            throw new IllegalArgumentException("Target ID must not be blank");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Reason must not be blank");
        }
        if (createdBy.isBlank()) {
            throw new IllegalArgumentException("CreatedBy must not be blank");
        }
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            throw new IllegalArgumentException("Expiration time must be in the future");
        }
    }

    /**
     * Checks if this override is permanent (no expiration).
     *
     * @return true if permanent
     */
    public boolean isPermanent() {
        return expiresAt == null;
    }

    /**
     * Returns the time-to-live duration if expiration is set.
     *
     * @return Optional containing the TTL, or empty if permanent
     */
    public Optional<Duration> getTtl() {
        if (expiresAt == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(Instant.now(), expiresAt));
    }

    /**
     * Builder for creating OverrideRequest instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for OverrideRequest.
     */
    public static class Builder {
        private String targetId;
        private OverrideScope scope;
        private OverrideCategory category;
        private DeviceValue value;
        private String reason;
        private Instant expiresAt;
        private String createdBy;

        public Builder targetId(String targetId) {
            this.targetId = targetId;
            return this;
        }

        public Builder scope(OverrideScope scope) {
            this.scope = scope;
            return this;
        }

        public Builder category(OverrideCategory category) {
            this.category = category;
            return this;
        }

        public Builder value(DeviceValue value) {
            this.value = value;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder ttl(Duration ttl) {
            this.expiresAt = ttl != null ? Instant.now().plus(ttl) : null;
            return this;
        }

        public Builder createdBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }

        public OverrideRequest build() {
            return new OverrideRequest(targetId, scope, category, value, reason, expiresAt, createdBy);
        }
    }
}

