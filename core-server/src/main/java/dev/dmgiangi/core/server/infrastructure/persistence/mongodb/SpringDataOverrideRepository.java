package dev.dmgiangi.core.server.infrastructure.persistence.mongodb;

import dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.DeviceOverrideDocument;
import dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.OverrideCategory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for DeviceOverride documents.
 */
public interface SpringDataOverrideRepository extends MongoRepository<DeviceOverrideDocument, String> {

    /**
     * Finds an override by target and category.
     */
    Optional<DeviceOverrideDocument> findByTargetIdAndCategory(String targetId, OverrideCategory category);

    /**
     * Finds all overrides for a target, ordered by category (highest first).
     */
    List<DeviceOverrideDocument> findByTargetIdOrderByCategoryDesc(String targetId);

    /**
     * Finds all active (not expired) overrides for a target.
     */
    @Query("{ 'targetId': ?0, $or: [ { 'expiresAt': null }, { 'expiresAt': { $gt: ?1 } } ] }")
    List<DeviceOverrideDocument> findActiveByTargetId(String targetId, Instant now);

    /**
     * Deletes an override by target and category.
     */
    void deleteByTargetIdAndCategory(String targetId, OverrideCategory category);

    /**
     * Deletes all overrides for a target.
     */
    void deleteAllByTargetId(String targetId);

    /**
     * Finds all expired overrides.
     */
    @Query("{ 'expiresAt': { $ne: null, $lt: ?0 } }")
    List<DeviceOverrideDocument> findExpired(Instant now);
}

