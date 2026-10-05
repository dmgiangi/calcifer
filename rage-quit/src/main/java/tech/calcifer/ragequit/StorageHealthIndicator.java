package tech.calcifer.ragequit;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("storageHealthIndicator")
public class StorageHealthIndicator implements HealthIndicator {
    private final SqliteDatabase database;

    public StorageHealthIndicator(SqliteDatabase database) { this.database = database; }

    @Override
    public Health health() {
        return database.healthy() ? Health.up().build() : Health.down().build();
    }
}
