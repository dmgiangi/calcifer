package tech.calcifer.auth.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;


class AuthorizationStateRoutingTest {

    @AfterEach
    void clearRoute() {
        RequestStateContext.clear();
    }

    @Test
    void redisFailureNeverRetriesAgainstNewIsolationStore() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        AuthorizationStateManager state = manager(
            bytes,
            properties(AuthorizationStateProperties.Role.HOME, 1),
            new MutableClock()
        );
        RedisOAuth2AuthorizationStore redis = new RedisOAuth2AuthorizationStore(bytes, "auth");
        RoutingOAuth2AuthorizationService service = new RoutingOAuth2AuthorizationService(state, redis);
        StateRoute connected = state.routeForStatefulRequest();
        RequestStateContext.bind(connected);
        bytes.available(false);

        assertThatThrownBy(() -> service.save(authorization())).isInstanceOf(StateUnavailableException.class);
        RequestStateContext.clear();
        assertThat(state.mode()).isEqualTo(AuthorizationStateManager.Mode.ISOLATED);

        StateRoute isolated = state.routeForStatefulRequest();
        RequestStateContext.bind(isolated);
        assertThat(service.findById("authorization-1")).isNull();
    }

    @Test
    void sessionsRejectObsoleteGenerationBeforeStoreLookup() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        AuthorizationStateManager state = manager(
            bytes,
            properties(AuthorizationStateProperties.Role.CLOUD, 3),
            new MutableClock()
        );
        RoutingSessionRepository sessions = new RoutingSessionRepository(state, bytes, "auth", Duration.ofMinutes(30));
        RequestStateContext.bind(StateRoute.redis(1));
        var session = sessions.createSession();
        sessions.save(session);
        RequestStateContext.clear();

        bytes.available(false);
        RequestStateContext.bind(StateRoute.redis(2));
        assertThat(sessions.findById(session.getId())).isNull();
    }

    @Test
    void incompatibleRedisSessionIsDiscardedAndRequiresFreshLogin() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        AuthorizationStateManager state = manager(
            bytes,
            properties(AuthorizationStateProperties.Role.CLOUD, 1),
            new MutableClock()
        );
        RoutingSessionRepository sessions = new RoutingSessionRepository(state, bytes, "auth", Duration.ofMinutes(30));
        String id = "r.3.corrupt";
        String key = "auth:g3:session:" + id;
        bytes.set(key, new byte[]{'C', 'A', 'S', 1, 0});
        RequestStateContext.bind(StateRoute.redis(3));

        assertThat(sessions.findById(id)).isNull();
        assertThat(bytes.keys()).doesNotContain(key);
    }

    @Test
    void authenticatedOidcSessionRoundTripsThroughRedis() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        AuthorizationStateManager state = manager(
            bytes,
            properties(AuthorizationStateProperties.Role.CLOUD, 1),
            new MutableClock()
        );
        RoutingSessionRepository sessions = new RoutingSessionRepository(state, bytes, "auth", Duration.ofMinutes(30));
        RequestStateContext.bind(StateRoute.redis(3));
        var session = sessions.createSession();
        Instant issuedAt = Instant.parse("2026-09-15T12:00:00Z");
        Map<String, Object> claims = Map.of(
            "sub",
            "google-subject",
            "email",
            "admin@example.test",
            "email_verified",
            true
        );
        OidcIdToken idToken = new OidcIdToken("id-token", issuedAt, issuedAt.plusSeconds(300), claims);
        DefaultOidcUser principal = new DefaultOidcUser(
            Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
            idToken,
            new OidcUserInfo(claims),
            "sub"
        );
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
            principal,
            principal.getAuthorities(),
            "google"
        );
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(authentication)
        );

        sessions.save(session);
        SecurityContextImpl restored = sessions
            .findById(session.getId())
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

        assertThat(restored.getAuthentication()).isInstanceOf(OAuth2AuthenticationToken.class);
        assertThat(restored.getAuthentication().getPrincipal()).isInstanceOf(DefaultOidcUser.class);
    }

    @Test
    void disconnectedHomeRestartCreatesFreshEpochAndCloudFailsClosed() {
        InMemoryRedisByteStore unavailable = new InMemoryRedisByteStore();
        unavailable.available(false);
        AuthorizationStateManager homeOne = manager(
            unavailable,
            properties(AuthorizationStateProperties.Role.HOME, 1),
            new MutableClock()
        );
        AuthorizationStateManager homeTwo = manager(
            unavailable,
            properties(AuthorizationStateProperties.Role.HOME, 1),
            new MutableClock()
        );
        homeOne.routeForStatefulRequest();
        homeTwo.routeForStatefulRequest();

        assertThat(homeOne.isolationEpoch()).isNotEqualTo(homeTwo.isolationEpoch());
        assertThat(homeOne.routeForStatefulRequest().owner()).isEqualTo(StateRoute.Owner.LOCAL);

        AuthorizationStateManager cloud = manager(
            unavailable,
            properties(AuthorizationStateProperties.Role.CLOUD, 1),
            new MutableClock()
        );
        assertThat(cloud.routeForStatefulRequest().owner()).isEqualTo(StateRoute.Owner.UNAVAILABLE);
        assertThat(cloud.mode()).isEqualTo(AuthorizationStateManager.Mode.CONNECTED);
        assertThat(cloud.isolationEpoch()).isNull();
    }

    @Test
    void connectedStateSurvivesManagerRestart() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        AuthorizationStateProperties properties = properties(AuthorizationStateProperties.Role.CLOUD, 1);
        AuthorizationStateManager first = manager(bytes, properties, new MutableClock());
        RoutingOAuth2AuthorizationService firstService = new RoutingOAuth2AuthorizationService(
            first,
            new RedisOAuth2AuthorizationStore(bytes, "auth")
        );
        RequestStateContext.bind(first.routeForStatefulRequest());
        firstService.save(authorization());
        RequestStateContext.clear();

        AuthorizationStateManager restarted = manager(bytes, properties, new MutableClock());
        RoutingOAuth2AuthorizationService restartedService = new RoutingOAuth2AuthorizationService(
            restarted,
            new RedisOAuth2AuthorizationStore(bytes, "auth")
        );
        RequestStateContext.bind(restarted.routeForStatefulRequest());

        assertThat(restartedService.findById("authorization-1").getId()).isEqualTo("authorization-1");
    }

    @Test
    void obsoleteIsolationEpochIsRejectedAfterRecoveryAndReisolation() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.available(false);
        MutableClock clock = new MutableClock();
        AuthorizationStateManager state = manager(bytes, properties(AuthorizationStateProperties.Role.HOME, 1), clock);
        RoutingOAuth2AuthorizationService service = new RoutingOAuth2AuthorizationService(
            state,
            new RedisOAuth2AuthorizationStore(bytes, "auth")
        );
        state.routeForStatefulRequest();
        StateRoute obsolete = state.routeForStatefulRequest();

        bytes.available(true);
        state.probe();
        clock.advance(Duration.ofSeconds(2));
        state.probe();
        bytes.available(false);
        state.routeForStatefulRequest();
        StateRoute current = state.routeForStatefulRequest();
        RequestStateContext.bind(obsolete);

        assertThat(current.isolationEpoch()).isNotEqualTo(obsolete.isolationEpoch());
        assertThatThrownBy(() -> service.findById("authorization-1"))
            .isInstanceOf(StateUnavailableException.class)
            .hasMessageContaining("obsolete");
    }

    private static OAuth2Authorization authorization() {
        RegisteredClient client = RegisteredClient
            .withId("registration")
            .clientId("grafana")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://grafana.calcifer.tech/login/generic_oauth")
            .build();
        return OAuth2Authorization
            .withRegisteredClient(client)
            .id("authorization-1")
            .principalName("user:admin")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("openid"))
            .build();
    }

    static AuthorizationStateManager manager(
        InMemoryRedisByteStore bytes,
        AuthorizationStateProperties properties,
        MutableClock clock) {
        RedisControlRepository control = new RedisControlRepository(bytes, "auth");
        RedisGenerationCleanup cleanup = new RedisGenerationCleanup(bytes, "auth", 100);
        AuthorizationStateTelemetry telemetry = new AuthorizationStateTelemetry(new SimpleMeterRegistry(), properties);
        return new AuthorizationStateManager(
            properties,
            control,
            cleanup,
            telemetry,
            clock,
            duration -> {},
            Runnable::run
        );
    }

    static AuthorizationStateProperties properties(AuthorizationStateProperties.Role role, int threshold) {
        return new AuthorizationStateProperties(
            true,
            role,
            new AuthorizationStateProperties.Redis("redis", 6379, "authorization", "redacted", "auth"),
            threshold,
            Duration.ofSeconds(2),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
            Duration.ofSeconds(3),
            100,
            Duration.ofMinutes(5)
        );
    }

    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-15T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
