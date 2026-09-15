package tech.calcifer.auth.state;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

final class AuthorizationStateHealthIndicator implements HealthIndicator {
  private final AuthorizationStateManager state;

  AuthorizationStateHealthIndicator(AuthorizationStateManager state) {
    this.state = state;
  }

  @Override
  public Health health() {
    boolean ready = state.mode() == AuthorizationStateManager.Mode.ISOLATED
        || (state.mode() == AuthorizationStateManager.Mode.CONNECTED && state.redisReachable());
    Health.Builder health = ready ? Health.up() : Health.outOfService();
    health.withDetail("role", state.role()).withDetail("mode", state.mode())
        .withDetail("redisReachable", state.redisReachable()).withDetail("statefulReady", ready);
    if (state.generation() > 0) health.withDetail("generation", state.generation());
    return health.build();
  }
}
