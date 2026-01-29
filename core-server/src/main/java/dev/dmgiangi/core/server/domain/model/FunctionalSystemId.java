package dev.dmgiangi.core.server.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing a unique identifier for a FunctionalSystem aggregate.
 * Uses UUID for globally unique identification.
 *
 * <p>Per Phase 0.2: FunctionalSystem is a DDD Aggregate Root. This ID serves as
 * the aggregate root identifier, ensuring uniqueness across the system.
 *
 * <p>Features:
 * <ul>
 *   <li>Immutable value object (Java record)</li>
 *   <li>UUID-based for global uniqueness</li>
 *   <li>Factory methods for generation and parsing</li>
 *   <li>String serialization for MongoDB compatibility</li>
 * </ul>
 *
 * @param value the UUID value (never null)
 */
public record FunctionalSystemId(UUID value) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if value is null
     */
    public FunctionalSystemId {
        Objects.requireNonNull(value, "FunctionalSystemId value must not be null");
    }

    /**
     * Generates a new random FunctionalSystemId.
     *
     * @return a new FunctionalSystemId with a random UUID
     */
    public static FunctionalSystemId generate() {
        return new FunctionalSystemId(UUID.randomUUID());
    }

    /**
     * Creates a FunctionalSystemId from a string representation.
     *
     * @param source the string representation of the UUID
     * @return a FunctionalSystemId parsed from the string
     * @throws IllegalArgumentException if the string is not a valid UUID format
     * @throws NullPointerException     if source is null
     */
    public static FunctionalSystemId fromString(final String source) {
        Objects.requireNonNull(source, "Source string must not be null");
        try {
            return new FunctionalSystemId(UUID.fromString(source));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid FunctionalSystemId format: '" + source + "'. Expected UUID format.", e);
        }
    }

    /**
     * Creates a FunctionalSystemId from an existing UUID.
     *
     * @param uuid the UUID value
     * @return a FunctionalSystemId wrapping the UUID
     * @throws NullPointerException if uuid is null
     */
    public static FunctionalSystemId of(final UUID uuid) {
        return new FunctionalSystemId(uuid);
    }

    /**
     * Returns the string representation of this ID (UUID format).
     * This format is used for MongoDB document IDs and API responses.
     *
     * @return the UUID as a string
     */
    @Override
    public String toString() {
        return value.toString();
    }

    /**
     * Returns the string representation for persistence.
     * Alias for toString() for clarity in persistence contexts.
     *
     * @return the UUID as a string
     */
    public String toStringValue() {
        return value.toString();
    }
}

