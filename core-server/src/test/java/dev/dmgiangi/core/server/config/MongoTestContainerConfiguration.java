package dev.dmgiangi.core.server.config;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MongoDB Testcontainers configuration for integration tests.
 * Uses @ServiceConnection for automatic property configuration in Spring Boot 4.
 */
@Testcontainers
public interface MongoTestContainerConfiguration {

    @Container
    @ServiceConnection
    MongoDBContainer mongodb = new MongoDBContainer("mongo:7.0");
}

