package dev.dmgiangi.core.server.infrastructure.persistence.mongodb;

import dev.dmgiangi.core.server.infrastructure.persistence.mongodb.document.FunctionalSystemDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

/**
 * Spring Data MongoDB repository for FunctionalSystem documents.
 */
public interface SpringDataFunctionalSystemRepository extends MongoRepository<FunctionalSystemDocument, String> {

    /**
     * Finds the system that contains a specific device.
     * Per 0.2: Exclusive membership - device belongs to max one system.
     *
     * @param deviceId the device ID
     * @return Optional containing the system if device is assigned
     */
    @Query("{ 'deviceIds': ?0 }")
    Optional<FunctionalSystemDocument> findByDeviceIdsContaining(String deviceId);
}

