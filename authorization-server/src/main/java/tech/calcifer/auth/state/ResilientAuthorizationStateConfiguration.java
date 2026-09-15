package tech.calcifer.auth.state;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.Ordered;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "identity.state", name = "enabled", havingValue = "true")
@EnableScheduling
@EnableSpringHttpSession
@ImportRuntimeHints(AuthorizationStateRuntimeHints.class)
class ResilientAuthorizationStateConfiguration {
  @Bean
  RedisConnectionFactory authorizationStateRedisConnectionFactory(AuthorizationStateProperties properties) {
    AuthorizationStateProperties.Redis redis = properties.redis();
    RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(redis.host(), redis.port());
    standalone.setUsername(redis.username());
    standalone.setPassword(RedisPassword.of(redis.password()));
    ClientOptions options = ClientOptions.builder()
        .timeoutOptions(TimeoutOptions.enabled(properties.probeInterval())).build();
    LettuceClientConfiguration client = LettuceClientConfiguration.builder()
        .commandTimeout(properties.probeInterval()).clientOptions(options).build();
    return new LettuceConnectionFactory(standalone, client);
  }

  @Bean
  RedisByteStore authorizationStateRedisByteStore(RedisConnectionFactory connections) {
    return new RedisByteStore(connections);
  }

  @Bean
  RedisControlRepository redisControlRepository(RedisByteStore store, AuthorizationStateProperties properties) {
    return new RedisControlRepository(store, properties.redis().namespace());
  }

  @Bean
  RedisGenerationCleanup redisGenerationCleanup(RedisByteStore store, AuthorizationStateProperties properties) {
    return new RedisGenerationCleanup(store, properties.redis().namespace(), properties.cleanupBatchSize());
  }

  @Bean
  TaskExecutor authorizationStateCleanupExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("authorization-state-cleanup-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(1);
    return executor;
  }

  @Bean
  AuthorizationStateTelemetry authorizationStateTelemetry(MeterRegistry meters,
      AuthorizationStateProperties properties) {
    return new AuthorizationStateTelemetry(meters, properties);
  }

  @Bean
  AuthorizationStateManager authorizationStateManager(AuthorizationStateProperties properties,
      RedisControlRepository control, RedisGenerationCleanup cleanup, AuthorizationStateTelemetry telemetry,
      TaskExecutor authorizationStateCleanupExecutor) {
    return new AuthorizationStateManager(properties, control, cleanup, telemetry, Clock.systemUTC(),
        duration -> Thread.sleep(duration.toMillis()), authorizationStateCleanupExecutor);
  }

  @Bean
  RedisOAuth2AuthorizationStore redisOAuth2AuthorizationStore(RedisByteStore store,
      AuthorizationStateProperties properties) {
    return new RedisOAuth2AuthorizationStore(store, properties.redis().namespace());
  }

  @Bean
  OAuth2AuthorizationService resilientAuthorizationService(AuthorizationStateManager state,
      RedisOAuth2AuthorizationStore redis) {
    return new RoutingOAuth2AuthorizationService(state, redis);
  }

  @Bean
  SessionRepository<MapSession> resilientSessionRepository(AuthorizationStateManager state,
      RedisByteStore redis, AuthorizationStateProperties properties) {
    return new RoutingSessionRepository(state, redis, properties.redis().namespace(), Duration.ofMinutes(30));
  }

  @Bean
  FilterRegistrationBean<StateRouteFilter> stateRouteFilter(AuthorizationStateManager state) {
    FilterRegistrationBean<StateRouteFilter> registration = new FilterRegistrationBean<>(new StateRouteFilter(state));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setName("authorizationStateRouteFilter");
    return registration;
  }

  @Bean
  AuthorizationStateHealthIndicator authorizationState(AuthorizationStateManager state) {
    return new AuthorizationStateHealthIndicator(state);
  }
}
