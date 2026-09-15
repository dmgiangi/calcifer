package tech.calcifer.auth.state;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;

final class AuthorizationStateManager {
  enum Mode { CONNECTED, ISOLATED, RECOVERING }

  @FunctionalInterface
  interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

  private record IsolationState(String epoch, OAuth2AuthorizationService authorizations,
                                ConcurrentMap<String, org.springframework.session.MapSession> sessions) {}

  private static final Logger log = LoggerFactory.getLogger(AuthorizationStateManager.class);
  private final AuthorizationStateProperties properties;
  private final RedisControlRepository control;
  private final RedisGenerationCleanup cleanup;
  private final AuthorizationStateTelemetry telemetry;
  private final Clock clock;
  private final Sleeper sleeper;
  private final TaskExecutor cleanupExecutor;
  private volatile Mode mode = Mode.CONNECTED;
  private volatile long generation;
  private volatile boolean redisReachable;
  private volatile int consecutiveFailures;
  private volatile Instant isolatedAt;
  private volatile Instant stableSince;
  private volatile IsolationState isolation;

  AuthorizationStateManager(AuthorizationStateProperties properties, RedisControlRepository control,
      RedisGenerationCleanup cleanup, AuthorizationStateTelemetry telemetry, Clock clock,
      Sleeper sleeper, TaskExecutor cleanupExecutor) {
    this.properties = properties;
    this.control = control;
    this.cleanup = cleanup;
    this.telemetry = telemetry;
    this.clock = clock;
    this.sleeper = sleeper;
    this.cleanupExecutor = cleanupExecutor;
  }

  synchronized StateRoute routeForStatefulRequest() {
    if (mode == Mode.ISOLATED) return StateRoute.local(isolation.epoch());
    try {
      RedisControlRepository.ControlState state = control.read();
      connectedOperationSucceeded();
      generation = state.generation();
      telemetry.generation(generation);
      if (state.gateOwner() != null) {
        transition(Mode.RECOVERING);
        return StateRoute.unavailable();
      }
      transition(Mode.CONNECTED);
      return StateRoute.redis(generation);
    } catch (RuntimeException exception) {
      connectedOperationFailed();
      return StateRoute.unavailable();
    }
  }

  synchronized void connectedOperationSucceeded() {
    redisReachable = true;
    consecutiveFailures = 0;
    telemetry.reachable(true);
  }

  synchronized void connectedOperationFailed() {
    redisReachable = false;
    telemetry.reachable(false);
    if (mode == Mode.ISOLATED) {
      stableSince = null;
      return;
    }
    consecutiveFailures++;
    if (properties.role() == AuthorizationStateProperties.Role.HOME
        && consecutiveFailures >= properties.failureThreshold()) enterIsolation();
  }

  @Scheduled(fixedDelayString = "${identity.state.probe-interval:2s}")
  void probe() {
    if (mode == Mode.ISOLATED) probeForRecovery();
    else routeForStatefulRequest();
  }

  private void probeForRecovery() {
    RedisControlRepository.ControlState observed;
    boolean ready;
    try {
      observed = control.read();
      synchronized (this) {
        redisReachable = true;
        telemetry.reachable(true);
        Instant now = clock.instant();
        if (stableSince == null) stableSince = now;
        ready = !before(now, isolatedAt.plus(properties.minimumIsolation()))
            && !before(now, stableSince.plus(properties.stableSuccess()));
      }
    } catch (RuntimeException exception) {
      synchronized (this) {
        redisReachable = false;
        stableSince = null;
        telemetry.reachable(false);
      }
      return;
    }
    if (ready) recover(observed);
  }

  private void recover(RedisControlRepository.ControlState observed) {
    String owner = UUID.randomUUID().toString();
    long expected = observed.generation();
    Instant started = clock.instant();
    if (!control.acquireLease(owner, properties.leaseTtl())
        || !control.acquireGate(owner, properties.gateTtl())) return;
    transition(Mode.RECOVERING);
    log.info("authorization_state_recovery_started role={} generation={}", properties.role(), expected);
    long advanced = 0;
    try {
      sleeper.sleep(properties.drain());
      advanced = control.compareAndAdvance(owner, expected);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException exception) {
      try {
        if (control.committed(owner, expected)) advanced = expected + 1;
      } catch (RuntimeException ignored) {
        // Expiring lease and gate safely release the unchanged generation.
      }
    }
    boolean committed = advanced == expected + 1;
    if (!committed) {
      try {
        committed = control.committed(owner, expected);
      } catch (RuntimeException ignored) {
        committed = false;
      }
    }
    if (committed) {
      finishRecovery(owner, advanced == 0 ? expected + 1 : advanced, started);
    } else {
      transition(Mode.ISOLATED);
      stableSince = null;
      telemetry.recovery("deferred", Duration.between(started, clock.instant()));
      log.warn("authorization_state_recovery_deferred role={} generation={}", properties.role(), expected);
    }
  }

  private synchronized void finishRecovery(String owner, long newGeneration, Instant started) {
    isolation = null;
    isolatedAt = null;
    stableSince = null;
    generation = newGeneration;
    telemetry.generation(newGeneration);
    connectedOperationSucceeded();
    transition(Mode.CONNECTED);
    Duration elapsed = Duration.between(started, clock.instant());
    telemetry.recovery("success", elapsed);
    log.info("authorization_state_recovery_completed role={} generation={} duration_ms={}",
        properties.role(), newGeneration, elapsed.toMillis());
    cleanupExecutor.execute(() -> cleanup.cleanup(newGeneration));
  }

  private void enterIsolation() {
    if (mode == Mode.ISOLATED) return;
    String epoch = UUID.randomUUID().toString();
    isolation = new IsolationState(epoch, new InMemoryOAuth2AuthorizationService(), new ConcurrentHashMap<>());
    isolatedAt = clock.instant();
    stableSince = null;
    transition(Mode.ISOLATED);
    log.warn("authorization_state_isolated role={}", properties.role());
  }

  OAuth2AuthorizationService localAuthorizations(StateRoute route) {
    return local(route).authorizations();
  }

  ConcurrentMap<String, org.springframework.session.MapSession> localSessions(StateRoute route) {
    return local(route).sessions();
  }

  private IsolationState local(StateRoute route) {
    IsolationState current = isolation;
    if (route.owner() != StateRoute.Owner.LOCAL || current == null
        || !current.epoch().equals(route.isolationEpoch())) {
      throw new StateUnavailableException("Isolation epoch is obsolete");
    }
    return current;
  }

  Mode mode() { return mode; }
  long generation() { return generation; }
  boolean redisReachable() { return redisReachable; }
  AuthorizationStateProperties.Role role() { return properties.role(); }
  String isolationEpoch() { return isolation == null ? null : isolation.epoch(); }

  private void transition(Mode target) {
    Mode previous = mode;
    mode = target;
    telemetry.transition(previous, target);
    if (previous != target) {
      log.info("authorization_state_transition role={} from={} to={} generation={}",
          properties.role(), previous, target, generation);
    }
  }

  private static boolean before(Instant left, Instant right) {
    return left.isBefore(right);
  }
}
