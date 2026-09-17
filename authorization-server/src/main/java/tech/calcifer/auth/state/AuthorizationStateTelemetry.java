package tech.calcifer.auth.state;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


final class AuthorizationStateTelemetry {

    private final MeterRegistry meters;
    private final String role;
    private final AtomicBoolean redisReachable = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0);
    private volatile AuthorizationStateManager.Mode mode = AuthorizationStateManager.Mode.CONNECTED;

    AuthorizationStateTelemetry(MeterRegistry meters, AuthorizationStateProperties properties) {
        this.meters = meters;
        this.role = properties.role().name();
        Gauge
            .builder("authorization.state.redis.reachable", redisReachable, value -> value.get() ? 1 : 0)
            .tag("role", role)
            .register(meters);
        Gauge.builder("authorization.state.generation", generation, AtomicLong::get).tag("role", role).register(meters);
        for (AuthorizationStateManager.Mode candidate : AuthorizationStateManager.Mode.values()) {
            Gauge
                .builder("authorization.state.mode", this, value -> value.mode == candidate ? 1 : 0)
                .tag("role", role)
                .tag("mode", candidate.name())
                .register(meters);
        }
    }

    void reachable(boolean value) {
        redisReachable.set(value);
    }

    void generation(long value) {
        generation.set(value);
    }

    void transition(AuthorizationStateManager.Mode from, AuthorizationStateManager.Mode to) {
        mode = to;
        if (from != to) {
            meters
                .counter("authorization.state.transitions", "role", role, "from", from.name(), "to", to.name())
                .increment();
        }
    }

    void recovery(String outcome, Duration duration) {
        meters.counter("authorization.state.recovery", "role", role, "outcome", outcome).increment();
        Timer
            .builder("authorization.state.recovery.duration")
            .tag("role", role)
            .tag("outcome", outcome)
            .register(meters)
            .record(duration);
    }
}
