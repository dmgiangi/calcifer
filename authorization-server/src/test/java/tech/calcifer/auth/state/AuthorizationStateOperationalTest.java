package tech.calcifer.auth.state;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.session.MapSession;

class AuthorizationStateOperationalTest {

    @Test
    void cleanupIsBoundedAndCannotDeleteActiveOrControlKeys() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.set("auth:control:generation", "2".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 120; i++) {
            bytes.set("auth:g1:authorization:" + i, new byte[]{1});
        }
        bytes.set("auth:g2:authorization:active", new byte[]{2});

        long removed = new RedisGenerationCleanup(bytes, "auth", 100).cleanup(2);

        assertThat(removed).isLessThanOrEqualTo(100);
        assertThat(bytes.keys()).contains("auth:control:generation", "auth:g2:authorization:active");
    }

    @Test
    void healthMetricsAndLogsContainOnlyBoundedOperationalDimensions() {
        AuthorizationStateProperties properties
            = AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.HOME, 1);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AuthorizationStateTelemetry telemetry = new AuthorizationStateTelemetry(meters, properties);
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.available(false);
        AuthorizationStateManager state = new AuthorizationStateManager(
            properties,
            new RedisControlRepository(bytes, "auth"),
            new RedisGenerationCleanup(bytes, "auth", 100),
            telemetry,
            new AuthorizationStateRoutingTest.MutableClock(),
            duration -> {},
            Runnable::run
        );
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuthorizationStateManager.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            state.routeForStatefulRequest();
        } finally {
            logger.detachAppender(logs);
        }

        var health = new AuthorizationStateHealthIndicator(state).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsOnlyKeys("role", "mode", "redisReachable", "statefulReady");
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter
            .getId()
            .getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("role", "mode", "from", "to", "outcome")));
        String diagnostics = health + meters.getMeters().toString() + logs.list
            .stream()
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
        assertThat(diagnostics).doesNotContain(
            "redacted",
            "user:admin",
            "session-id",
            "authorization-code",
            "access-token",
            "password"
        );
    }

    @Test
    void filterKeepsMetadataAvailableAndGatesStatefulEndpoints() throws Exception {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.available(false);
        AuthorizationStateManager cloud = AuthorizationStateRoutingTest.manager(
            bytes,
            AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.CLOUD, 1),
            new AuthorizationStateRoutingTest.MutableClock()
        );
        StateRouteFilter filter = new StateRouteFilter(cloud);
        MockHttpServletResponse metadataResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/oauth2/jwks"), metadataResponse, new MockFilterChain());
        assertThat(metadataResponse.getStatus()).isEqualTo(200);

        MockHttpServletResponse tokenResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/oauth2/token"), tokenResponse, new MockFilterChain());
        assertThat(tokenResponse.getStatus()).isEqualTo(503);
        assertThat(tokenResponse.getContentAsString()).isEqualTo("{\"error\":\"temporarily_unavailable\"}");
    }

    @Test
    void isolatedHomeGatesGoogleButKeepsPasswordLoginAvailable() throws Exception {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        bytes.available(false);
        AuthorizationStateManager home = AuthorizationStateRoutingTest.manager(
            bytes,
            AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.HOME, 1),
            new AuthorizationStateRoutingTest.MutableClock()
        );
        home.routeForStatefulRequest();
        StateRouteFilter filter = new StateRouteFilter(home);

        MockHttpServletResponse googleResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/oauth2/authorization/google"), googleResponse, new MockFilterChain());
        assertThat(googleResponse.getStatus()).isEqualTo(503);

        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/login"), loginResponse, new MockFilterChain());
        assertThat(loginResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void nativeHintsRegisterSessionSerialization() {
        RuntimeHints hints = new RuntimeHints();

        new AuthorizationStateRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(hints.serialization().javaSerializationHints())
            .extracting(hint -> hint.getType().getName())
            .contains(MapSession.class.getName(), Instant.class.getName(), Duration.class.getName(), "java.time.Ser", HashMap.class.getName(),
                HashSet.class.getName(), LinkedHashMap.class.getName(), LinkedHashSet.class.getName(),
                AuthorizationGrantType.class.getName(), OAuth2AuthorizationRequest.class.getName(),
                OAuth2AuthorizationResponseType.class.getName(), Collections.unmodifiableMap(new HashMap<>()).getClass().getName(),
                Collections.unmodifiableSet(new HashSet<>()).getClass().getName(), OAuth2AuthenticationToken.class.getName(),
                AbstractOAuth2Token.class.getName(), OidcIdToken.class.getName(), OidcUserInfo.class.getName(),
                DefaultOAuth2User.class.getName(), DefaultOidcUser.class.getName(), Boolean.class.getName(),
                Integer.class.getName(), Long.class.getName(), Double.class.getName(), URL.class.getName(),
                String[].class.getTypeName(), "java.util.CollSer");
    }

    @Test
    void validatesEnabledRedisAndRecoveryConfiguration() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            AuthorizationStateProperties valid
                = AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.CLOUD, 3);
            AuthorizationStateProperties invalid = new AuthorizationStateProperties(
                true,
                AuthorizationStateProperties.Role.CLOUD,
                new AuthorizationStateProperties.Redis("", 6379, "", "", "bad:namespace"),
                3,
                Duration.ZERO,
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                100,
                Duration.ofMinutes(5)
            );

            assertThat(validator.validate(valid)).isEmpty();
            assertThat(validator.validate(invalid)).isNotEmpty();
        }
    }
}
