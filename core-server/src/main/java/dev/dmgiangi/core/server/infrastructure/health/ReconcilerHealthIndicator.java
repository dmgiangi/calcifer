package dev.dmgiangi.core.server.infrastructure.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Health indicator for the Device State Reconciler.
 * <p>
 * Reports the reconciler as healthy if it has run successfully within the expected interval.
 * This helps detect if the scheduled reconciliation task has stopped running.
 */
@Component
@RequiredArgsConstructor
public class ReconcilerHealthIndicator implements HealthIndicator {

    private static final Duration MAX_ALLOWED_DELAY = Duration.ofSeconds(30);

    private final AtomicReference<Instant> lastSuccessfulRun = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailedRun = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    @Override
    public Health health() {
        final var lastSuccess = lastSuccessfulRun.get();
        final var lastFailure = lastFailedRun.get();

        if (lastSuccess == null && lastFailure == null) {
            // Reconciler hasn't run yet - could be startup
            return Health.unknown()
                    .withDetail("status", "NOT_YET_RUN")
                    .withDetail("message", "Reconciler has not executed yet")
                    .build();
        }

        final var now = Instant.now();

        // Check if last run was too long ago
        if (lastSuccess != null) {
            final var timeSinceLastRun = Duration.between(lastSuccess, now);
            if (timeSinceLastRun.compareTo(MAX_ALLOWED_DELAY) > 0) {
                return Health.down()
                        .withDetail("status", "STALE")
                        .withDetail("lastSuccessfulRun", lastSuccess.toString())
                        .withDetail("timeSinceLastRun", timeSinceLastRun.toSeconds() + "s")
                        .withDetail("maxAllowedDelay", MAX_ALLOWED_DELAY.toSeconds() + "s")
                        .build();
            }
        }

        // Check if there was a recent failure
        if (lastFailure != null && (lastSuccess == null || lastFailure.isAfter(lastSuccess))) {
            return Health.down()
                    .withDetail("status", "FAILED")
                    .withDetail("lastFailedRun", lastFailure.toString())
                    .withDetail("error", lastError.get())
                    .build();
        }

        // Healthy
        return Health.up()
                .withDetail("status", "RUNNING")
                .withDetail("lastSuccessfulRun", lastSuccess.toString())
                .build();
    }

    /**
     * Called by the reconciler after a successful run.
     */
    public void recordSuccess() {
        lastSuccessfulRun.set(Instant.now());
    }

    /**
     * Called by the reconciler after a failed run.
     *
     * @param error the error message
     */
    public void recordFailure(final String error) {
        lastFailedRun.set(Instant.now());
        lastError.set(error);
    }
}

