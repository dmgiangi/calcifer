package dev.dmgiangi.core.server.infrastructure.scheduling;

import dev.dmgiangi.core.server.infrastructure.persistence.redis.RedisDeviceStateRepositoryAdapter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;


/**
 * Scheduled job for detecting stale devices.
 * Per Phase 0.15: Runs daily at 3 AM to detect devices with no activity >7 days.
 *
 * <p>Stale devices are logged and counted for alerting, but NOT automatically deleted.
 * Manual investigation is required before decommissioning.
 */
@Slf4j
@Component
public class StaleDeviceDetectionJob {

    private static final String METRIC_STALE_DEVICES = "calcifer.maintenance.stale_devices";
    private static final Duration STALENESS_THRESHOLD = Duration.ofDays(7);

    private final RedisDeviceStateRepositoryAdapter redisAdapter;
    private final Counter staleDevicesCounter;

    public StaleDeviceDetectionJob(RedisDeviceStateRepositoryAdapter redisAdapter,
                                   MeterRegistry meterRegistry) {
        this.redisAdapter = redisAdapter;
        this.staleDevicesCounter = Counter.builder(METRIC_STALE_DEVICES)
                .description("Number of stale devices detected (no activity >7 days)")
                .register(meterRegistry);
    }

    /**
     * Runs daily at 3:00 AM to detect stale devices.
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "${app.maintenance.stale-detection-cron:0 0 3 * * *}")
    public void detectStaleDevices() {
        log.info("Starting stale device detection job");

        final var now = Instant.now();
        final var threshold = now.minus(STALENESS_THRESHOLD);
        final var indexedKeys = redisAdapter.getAllIndexedDeviceKeys();

        int staleCount = 0; // mutable - counts stale devices found

        for (final var key : indexedKeys) {
            final var deviceIdOpt = redisAdapter.extractDeviceIdFromKey(key);
            if (deviceIdOpt.isEmpty()) {
                log.warn("Invalid key format in index: {}", key);
                continue;
            }

            final var deviceId = deviceIdOpt.get();
            final var lastActivityOpt = redisAdapter.findLastActivity(deviceId);

            if (lastActivityOpt.isEmpty()) {
                // Device exists in index but has no lastActivity - treat as stale
                log.warn("STALE DEVICE DETECTED: {} - no lastActivity timestamp (never updated with new format)", deviceId);
                staleCount++;
                continue;
            }

            final var lastActivity = lastActivityOpt.get();
            if (lastActivity.isBefore(threshold)) {
                final var daysSinceActivity = Duration.between(lastActivity, now).toDays();
                log.warn("STALE DEVICE DETECTED: {} - last activity {} days ago ({})",
                        deviceId, daysSinceActivity, lastActivity);
                staleCount++;
            }
        }

        if (staleCount > 0) {
            staleDevicesCounter.increment(staleCount);
            log.warn("Stale device detection completed: {} stale devices found out of {} indexed",
                    staleCount, indexedKeys.size());
        } else {
            log.info("Stale device detection completed: no stale devices found ({} indexed)",
                    indexedKeys.size());
        }
    }
}

