package dev.dmgiangi.core.server.infrastructure.scheduling;

import dev.dmgiangi.core.server.infrastructure.persistence.redis.RedisDeviceStateRepositoryAdapter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Scheduled job for cleaning up orphan index entries.
 * Per Phase 0.15: Runs daily at 4 AM to remove entries from index:active:outputs
 * where the device key no longer exists.
 *
 * <p>This prevents index bloat from renamed/removed devices.
 */
@Slf4j
@Component
public class OrphanIndexCleanupJob {

    private static final String METRIC_ORPHANS_CLEANED = "calcifer.maintenance.orphans_cleaned";

    private final RedisDeviceStateRepositoryAdapter redisAdapter;
    private final Counter orphansCleanedCounter;

    public OrphanIndexCleanupJob(RedisDeviceStateRepositoryAdapter redisAdapter,
                                 MeterRegistry meterRegistry) {
        this.redisAdapter = redisAdapter;
        this.orphansCleanedCounter = Counter.builder(METRIC_ORPHANS_CLEANED)
                .description("Number of orphan index entries cleaned up")
                .register(meterRegistry);
    }

    /**
     * Runs daily at 4:00 AM to clean up orphan index entries.
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "${app.maintenance.orphan-cleanup-cron:0 0 4 * * *}")
    public void cleanupOrphanIndexEntries() {
        log.info("Starting orphan index cleanup job");

        final var indexedKeys = redisAdapter.getAllIndexedDeviceKeys();
        int orphanCount = 0; // mutable - counts orphan entries removed

        for (final var key : indexedKeys) {
            if (!redisAdapter.keyExists(key)) {
                log.warn("ORPHAN INDEX ENTRY: {} - device key no longer exists, removing from index", key);
                redisAdapter.removeFromIndex(key);
                orphanCount++;
            }
        }

        if (orphanCount > 0) {
            orphansCleanedCounter.increment(orphanCount);
            log.warn("Orphan index cleanup completed: {} orphan entries removed out of {} indexed",
                    orphanCount, indexedKeys.size());
        } else {
            log.info("Orphan index cleanup completed: no orphan entries found ({} indexed)",
                    indexedKeys.size());
        }
    }
}

