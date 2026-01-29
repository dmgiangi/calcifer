package dev.dmgiangi.core.server.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dmgiangi.core.server.application.override.OverrideValidationResult;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideCategory;
import dev.dmgiangi.core.server.domain.port.OverrideRepository.OverrideScope;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for override operations.
 * Per Phase 5.9: Contains outcome, applied value, reason, and warnings.
 *
 * @param targetId     the target ID (device or system)
 * @param scope        the override scope (DEVICE or SYSTEM)
 * @param category     the override category
 * @param outcome      the outcome of the operation (APPLIED, BLOCKED, MODIFIED)
 * @param appliedValue the value that was applied (null if blocked)
 * @param reason       the reason for the override or blocking
 * @param warnings     any warnings generated during validation
 * @param expiresAt    when the override expires (null if permanent)
 * @param createdAt    when the override was created
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OverrideResponse(
        String targetId,
        OverrideScope scope,
        OverrideCategory category,
        Outcome outcome,
        Object appliedValue,
        Object originalValue,
        String reason,
        List<String> warnings,
        List<String> affectedRules,
        Instant expiresAt,
        Instant createdAt
) {
    /**
     * Outcome of an override operation.
     */
    public enum Outcome {
        /**
         * Override was applied as requested
         */
        APPLIED,
        /**
         * Override was blocked by safety rules
         */
        BLOCKED,
        /**
         * Override value was modified to comply with safety rules
         */
        MODIFIED,
        /**
         * Override was cancelled
         */
        CANCELLED,
        /**
         * Override was not found (for cancel operations)
         */
        NOT_FOUND
    }

    /**
     * Creates a response from an OverrideValidationResult.
     *
     * @param result the validation result
     * @return the response DTO
     */
    public static OverrideResponse fromValidationResult(final OverrideValidationResult result) {
        return switch (result) {
            case OverrideValidationResult.Applied applied -> new OverrideResponse(
                    applied.override().targetId(),
                    applied.override().scope(),
                    applied.override().category(),
                    Outcome.APPLIED,
                    extractRawValueFromObject(applied.override().value()),
                    null,
                    applied.override().reason(),
                    applied.warnings(),
                    List.of(),
                    applied.override().expiresAt(),
                    applied.override().createdAt()
            );
            case OverrideValidationResult.Blocked blocked -> new OverrideResponse(
                    null,
                    null,
                    null,
                    Outcome.BLOCKED,
                    null,
                    null,
                    blocked.reason(),
                    blocked.warnings(),
                    blocked.blockingRules(),
                    null,
                    null
            );
            case OverrideValidationResult.Modified modified -> new OverrideResponse(
                    modified.override().targetId(),
                    modified.override().scope(),
                    modified.override().category(),
                    Outcome.MODIFIED,
                    extractRawValueFromDeviceValue(modified.modifiedValue()),
                    extractRawValueFromDeviceValue(modified.originalValue()),
                    modified.override().reason(),
                    modified.warnings(),
                    modified.modifyingRules(),
                    modified.override().expiresAt(),
                    modified.override().createdAt()
            );
        };
    }

    /**
     * Creates a cancelled response.
     *
     * @param targetId the target ID
     * @param scope    the scope
     * @param category the category
     * @return the response DTO
     */
    public static OverrideResponse cancelled(
            final String targetId,
            final OverrideScope scope,
            final OverrideCategory category
    ) {
        return new OverrideResponse(
                targetId, scope, category, Outcome.CANCELLED,
                null, null, "Override cancelled successfully",
                List.of(), List.of(), null, null
        );
    }

    /**
     * Creates a not-found response.
     *
     * @param targetId the target ID
     * @param scope    the scope
     * @param category the category
     * @return the response DTO
     */
    public static OverrideResponse notFound(
            final String targetId,
            final OverrideScope scope,
            final OverrideCategory category
    ) {
        return new OverrideResponse(
                targetId, scope, category, Outcome.NOT_FOUND,
                null, null, "No active override found for the specified target and category",
                List.of(), List.of(), null, null
        );
    }

    /**
     * Extracts the raw value from a DeviceValue for JSON serialization.
     */
    private static Object extractRawValueFromDeviceValue(final DeviceValue value) {
        if (value == null) return null;
        return switch (value) {
            case dev.dmgiangi.core.server.domain.model.RelayValue r -> r.state();
            case dev.dmgiangi.core.server.domain.model.FanValue f -> f.speed();
        };
    }

    /**
     * Extracts the raw value from an Object (which may be DeviceValue or primitive).
     */
    private static Object extractRawValueFromObject(final Object value) {
        if (value == null) return null;
        if (value instanceof DeviceValue dv) {
            return extractRawValueFromDeviceValue(dv);
        }
        // Already a primitive or simple type
        return value;
    }
}

