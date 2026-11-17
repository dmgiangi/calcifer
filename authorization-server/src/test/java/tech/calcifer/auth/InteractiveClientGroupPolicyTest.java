package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;


class InteractiveClientGroupPolicyTest {

    @Test
    void defaultsInteractiveClientsToAdminAndHonorsExplicitGroupPolicy() {
        InteractiveClientGroupPolicy policy = new InteractiveClientGroupPolicy(properties());
        var administrator = IdentityTestAuthentications.password("admin@example.test");
        var participant = IdentityTestAuthentications.google("alice@example.test");

        assertThat(policy.allows("grafana", administrator)).isTrue();
        assertThat(policy.allows("grafana", participant)).isFalse();
        assertThat(policy.allows("restricted-client", participant)).isTrue();
        assertThat(policy.allows("restricted-client", administrator)).isFalse();
        assertThat(policy.allows("unknown", administrator)).isFalse();
    }

    @Test
    void resolvesGoogleUserFromVerifiedPrincipalEmailRatherThanProviderSubject() {
        Instant issuedAt = Instant.parse("2026-09-19T23:23:00Z");
        OidcIdToken idToken = OidcIdToken.withTokenValue("token")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .subject("google-subject")
            .claim("email", "alice@example.test")
            .claim("email_verified", true)
            .build();
        var googleUser = new DefaultOidcUser(
            Set.of(new SimpleGrantedAuthority("ROLE_PARTICIPANTS")), idToken, (OidcUserInfo) null
        );
        var authentication = new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
            googleUser, googleUser.getAuthorities(), "google");

        assertThat(new InteractiveClientGroupPolicy(properties()).allows("restricted-client", authentication)).isTrue();
    }

    @Test
    void rejectsAuthorizationRequestWhenUserHasNoPermittedClientGroup() {
        var principal = IdentityTestAuthentications.google("alice@example.test");
        var request = new OAuth2AuthorizationCodeRequestAuthenticationToken(
            "https://auth.example.test/oauth2/authorize",
            "grafana",
            principal,
            "https://grafana.example.test/login/generic_oauth",
            "state",
            Set.of("openid"),
            Map.of()
        );
        var registeredClient = RegisteredClient.withId("grafana-registered")
            .clientId("grafana")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://grafana.example.test/login/generic_oauth")
            .build();
        var context = OAuth2AuthorizationCodeRequestAuthenticationContext.with(request)
            .registeredClient(registeredClient)
            .build();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> new InteractiveClientGroupPolicy(properties()).validate(context)
        )).isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
    }

    @Test
    void doesNotApplyGroupPolicyBeforeTheUserHasAuthenticated() {
        var principal = new TestingAuthenticationToken("anonymousUser", "ignored");
        var request = new OAuth2AuthorizationCodeRequestAuthenticationToken(
            "https://auth.example.test/oauth2/authorize",
            "grafana",
            principal,
            "https://grafana.example.test/login/generic_oauth",
            "state",
            Set.of("openid"),
            Map.of()
        );
        var registeredClient = RegisteredClient.withId("grafana-registered")
            .clientId("grafana")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://grafana.example.test/login/generic_oauth")
            .build();
        var context = OAuth2AuthorizationCodeRequestAuthenticationContext.with(request)
            .registeredClient(registeredClient)
            .build();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> new InteractiveClientGroupPolicy(properties()).validate(context)
        )).isNull();
    }

    static IdentityProperties properties() {
        var admin = new IdentityProperties.User(
            "admin@example.test", "user:admin", Set.of("admin"), Set.of("google", "password")
        );
        var participant = new IdentityProperties.User(
            "alice@example.test", "user:alice", Set.of("participants"), Set.of("google")
        );
        var grafana = new IdentityProperties.Client(
            "grafana", "test-legacy-secret", "https://grafana.example.test/login/generic_oauth", "grafana"
        );
        var api = new IdentityProperties.Client(
            "grafana-api", "test-legacy-secret", "https://grafana.example.test", "grafana"
        );
        var restrictedClient = new IdentityProperties.ClientDefinition(
            "restricted-client",
            "test-client-secret",
            Set.of("https://client.example.test/login/oauth2/code/auth"),
            Set.of("openid", "profile", "email"),
            Set.of("authorization_code"),
            Set.of("client_secret_basic"),
            "restricted-client",
            true,
            null,
            Set.of("participants")
        );
        return new IdentityProperties(
            "https://auth.example.test",
            "file:key",
            Set.of("admin", "participants"),
            Map.of("administrator", admin, "alice", participant),
            grafana,
            api,
            new IdentityProperties.LocalLogin(true, "admin@example.test", "{bcrypt}unused-test-hash"),
            Map.of("restricted-client", restrictedClient)
        );
    }
}
