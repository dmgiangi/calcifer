package dev.dmgiangi.core.server.infrastructure.health;

import dev.dmgiangi.core.server.infrastructure.health.event.InfrastructureComponent;
import dev.dmgiangi.core.server.infrastructure.health.event.InfrastructureFailureEvent;
import dev.dmgiangi.core.server.infrastructure.health.event.InfrastructureRecoveryEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Infrastructure Health Gate - implements fail-stop pattern for IoT safety.
 *
 * <p>Monitors critical infrastructure (Redis, MongoDB) and blocks command generation
 * when any component is unhealthy. This is safer than circuit breaker fallbacks
 * because devices have their own hardware fail-safe mechanisms.
 *
 * <p>When infrastructure fails:
 * <ul>
 *   <li>Command generation is halted (Reconciler checks {@link #isHealthy()})</li>
 *   <li>{@link InfrastructureFailureEvent} is published for alerting</li>
 *   <li>Devices fail-safe to hardware defaults autonomously</li>
 * </ul>
 *
 * <p>When infrastructure recovers:
 * <ul>
 *   <li>{@link InfrastructureRecoveryEvent} is published</li>
 *   <li>Command generation resumes automatically</li>
 * </ul>
 */
@Slf4j
@Component
public class InfrastructureHealthGate {

    private static final String METRIC_FAILURES = "calcifer.infrastructure.failures";
    private static final String METRIC_STATUS = "calcifer.infrastructure.status";
    private static final String TAG_COMPONENT = "component";

    private final HealthContributorRegistry healthContributorRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    // Track state per component: true = healthy, false = unhealthy
    private final Map<InfrastructureComponent, AtomicBoolean> componentHealthy = new EnumMap<>(InfrastructureComponent.class);
    private final Map<InfrastructureComponent, AtomicReference<Instant>> failureStartTime = new EnumMap<>(InfrastructureComponent.class);
    private final Map<InfrastructureComponent, Counter> failureCounters = new EnumMap<>(InfrastructureComponent.class);

    public InfrastructureHealthGate(final HealthContributorRegistry healthContributorRegistry,
                                    final ApplicationEventPublisher eventPublisher,
                                    final MeterRegistry meterRegistry) {
        this.healthContributorRegistry = healthContributorRegistry;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;

        // Initialize state tracking and metrics for each component
        for (final var component : InfrastructureComponent.values()) {
            componentHealthy.put(component, new AtomicBoolean(true)); // Assume healthy at startup
            failureStartTime.put(component, new AtomicReference<>());

            // Create failure counter
            final var counter = Counter.builder(METRIC_FAILURES)
                    .description("Number of infrastructure failures detected")
                    .tags(Tags.of(TAG_COMPONENT, component.name().toLowerCase()))
                    .register(meterRegistry);
            failureCounters.put(component, counter);

            // Create status gauge (1 = healthy, 0 = unhealthy)
            final var healthyRef = componentHealthy.get(component);
            meterRegistry.gauge(METRIC_STATUS,
                    Tags.of(TAG_COMPONENT, component.name().toLowerCase()),
                    healthyRef,
                    ref -> ref.get() ? 1.0 : 0.0);
        }
    }

    /**
     * Checks if all critical infrastructure is healthy.
     *
     * <p>The Reconciler should call this before generating commands.
     * If this returns false, command generation should be skipped.
     *
     * @return true if all infrastructure is healthy, false otherwise
     */
    public boolean isHealthy() {
        return componentHealthy.values().stream().allMatch(AtomicBoolean::get);
    }

    /**
     * Checks if a specific infrastructure component is healthy.
     *
     * @param component the component to check
     * @return true if the component is healthy, false otherwise
     */
    public boolean isHealthy(final InfrastructureComponent component) {
        return componentHealthy.get(component).get();
    }

    /**
     * Scheduled health check - runs every 5 seconds to detect infrastructure state changes.
     */
    @Scheduled(fixedRateString = "${app.infrastructure.health-check-interval-ms:5000}")
    public void checkInfrastructureHealth() {
        checkComponent(InfrastructureComponent.REDIS, "redis");
        checkComponent(InfrastructureComponent.MONGODB, "mongo");
        // RabbitMQ check can be added when rabbit health indicator is configured
    }

    private void checkComponent(final InfrastructureComponent component, final String healthIndicatorName) {
        final var contributor = healthContributorRegistry.getContributor(healthIndicatorName);

        if (contributor == null) {
            log.trace("Health indicator '{}' not found, assuming healthy", healthIndicatorName);
            return;
        }

        final var health = getHealth(contributor);
        if (health == null) {
            log.trace("Could not get health for '{}', assuming healthy", healthIndicatorName);
            return;
        }

        final var isUp = Status.UP.equals(health.getStatus());
        final var wasHealthy = componentHealthy.get(component).get();

        if (wasHealthy && !isUp) {
            // Transition: HEALTHY → UNHEALTHY
            handleFailure(component, health);
        } else if (!wasHealthy && isUp) {
            // Transition: UNHEALTHY → HEALTHY
            handleRecovery(component);
        }
    }

    private Health getHealth(final HealthContributor contributor) {
        if (contributor instanceof HealthIndicator healthIndicator) {
            return healthIndicator.health();
        }
        return null;
    }

    private void handleFailure(final InfrastructureComponent component, final Health health) {
        componentHealthy.get(component).set(false);
        failureStartTime.get(component).set(Instant.now());
        failureCounters.get(component).increment();

        final var errorMessage = extractErrorMessage(health);

        log.error("INFRASTRUCTURE FAILURE: {} is DOWN - {}. Command generation HALTED.",
                component, errorMessage);

        final var event = new InfrastructureFailureEvent(this, component, errorMessage);
        eventPublisher.publishEvent(event);
    }

    private void handleRecovery(final InfrastructureComponent component) {
        componentHealthy.get(component).set(true);

        final var failedAt = failureStartTime.get(component).getAndSet(null);
        final var downtime = failedAt != null
                ? Duration.between(failedAt, Instant.now())
                : Duration.ZERO;

        log.info("INFRASTRUCTURE RECOVERY: {} is UP after {} downtime. Command generation RESUMED.",
                component, formatDuration(downtime));

        final var event = new InfrastructureRecoveryEvent(this, component, downtime);
        eventPublisher.publishEvent(event);
    }

    private String extractErrorMessage(final Health health) {
        final var details = health.getDetails();
        if (details.containsKey("error")) {
            return details.get("error").toString();
        }
        return "Status: " + health.getStatus().getCode();
    }

    private String formatDuration(final Duration duration) {
        if (duration.toHours() > 0) {
            return "%dh %dm %ds".formatted(duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
        } else if (duration.toMinutes() > 0) {
            return "%dm %ds".formatted(duration.toMinutes(), duration.toSecondsPart());
        } else {
            return "%ds".formatted(duration.toSeconds());
        }
    }
}

