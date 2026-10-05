package tech.calcifer.auth.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tech.calcifer.auth.RageQuitTestSupport;

/** Two synthetic edges share the real versioned Redis byte serialization, not live Redis. */
class RageQuitRedisPolicyTest {

    @AfterEach
    void clearRoute() { RequestStateContext.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"dem.gianluigi@gmail.com", "pugliens@gmail.com", "frevadiscor@gmail.com", "password"})
    void crossEdgeSessionAndAuthorizationRoundTripPreserveTrustedSourceAndIdentity(String identity) {
        var bytes = new InMemoryRedisByteStore();
        var cloud = AuthorizationStateRoutingTest.manager(bytes,
            AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.CLOUD, 1),
            new AuthorizationStateRoutingTest.MutableClock());
        var home = AuthorizationStateRoutingTest.manager(bytes,
            AuthorizationStateRoutingTest.properties(AuthorizationStateProperties.Role.HOME, 1),
            new AuthorizationStateRoutingTest.MutableClock());
        var cloudSessions = new RoutingSessionRepository(cloud, bytes, "auth", Duration.ofMinutes(30));
        var homeSessions = new RoutingSessionRepository(home, bytes, "auth", Duration.ofMinutes(30));
        Authentication authentication = identity.equals("password") ? RageQuitTestSupport.password()
            : RageQuitTestSupport.google(identity);
        RequestStateContext.bind(StateRoute.redis(3));
        var session = cloudSessions.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(authentication));
        cloudSessions.save(session);
        SecurityContextImpl security = homeSessions.findById(session.getId())
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var restored = security.getAuthentication();
        assertThat(restored.getClass()).isEqualTo(authentication.getClass());
        assertThat(restored.getName()).isEqualTo(authentication.getName());

        var clients = new org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository(
            org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId("registered-rage")
                .clientId("rage-quit").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(RageQuitTestSupport.CALLBACK).build());
        var authorization = OAuth2Authorization.withRegisteredClient(clients.findByClientId("rage-quit"))
            .principalName(authentication.getName()).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .attribute(Principal.class.getName(), authentication)
            .token(new OAuth2AuthorizationCode("synthetic-code", Instant.now(), Instant.now().plusSeconds(60))).build();
        var cloudAuthorizations = new RedisOAuth2AuthorizationStore(bytes, "auth");
        cloudAuthorizations.save(3, authorization);
        var homeAuthorizations = new RedisOAuth2AuthorizationStore(bytes, "auth");
        Authentication restoredCodePrincipal = homeAuthorizations.findById(3, authorization.getId())
            .getAttribute(Principal.class.getName());
        // Use the public production property contract to check catalog identity and provenance after both round trips.
        for (Authentication principal : java.util.List.of(restored, restoredCodePrincipal)) {
            assertThat(principal.getClass()).isEqualTo(authentication.getClass());
            String email = identity.equals("password") ? RageQuitTestSupport.ADMIN : identity;
            assertThat(RageQuitTestSupport.properties().userByEmail(email).canonicalSubject())
                .isEqualTo(RageQuitTestSupport.SUBJECTS.get(email));
            if (principal instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken google) {
                assertThat(google.getAuthorizedClientRegistrationId()).isEqualTo("google");
                var user = (org.springframework.security.oauth2.core.oidc.user.OidcUser) google.getPrincipal();
                assertThat(user.getEmail()).isEqualTo(email);
                assertThat(user.getEmailVerified()).isTrue();
            }
        }
        tech.calcifer.auth.RageQuitRestoredPolicyAssertions.verify(restored, identity.equals("password"));
        tech.calcifer.auth.RageQuitRestoredPolicyAssertions.verify(restoredCodePrincipal, identity.equals("password"));
    }
}