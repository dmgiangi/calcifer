package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;


class AuthorizationClaimsCustomizerTest {

    private static final IdentityProperties PROPERTIES = new IdentityProperties(
        "https://auth.example.test",
        "admin@example.test",
        "user:admin",
        "file:key",
        new IdentityProperties.Client(
            "grafana",
            "secret",
            "https://grafana.example.test/login/generic_oauth",
            "grafana"
        ),
        new IdentityProperties.Client("grafana-api", "secret", "https://grafana.example.test", "grafana"),
        new IdentityProperties.LocalLogin(true, "admin@example.test", "{bcrypt}hash")
    );

    @Test
    void mapsInteractiveAuthenticationToCanonicalSubject() {
        var context = context(
            AuthorizationGrantType.AUTHORIZATION_CODE,
            new OAuth2TokenType("access_token"),
            "grafana",
            "admin@example.test"
        );

        new AuthorizationServerConfiguration().jwtClaimsCustomizer(PROPERTIES).customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getSubject()).isEqualTo("user:admin");
        assertThat(claims.getClaimAsString("email")).isEqualTo("admin@example.test");
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("admin");
        assertThat(claims.getClaimAsStringList("groups")).containsExactly("admin");
        assertThat(claims.getAudience()).containsExactly("grafana");
    }

    @Test
    void mapsGoogleOnlyUserToTheirConfiguredSubjectAndGroups() {
        IdentityProperties.User administrator = new IdentityProperties.User(
            "admin@example.test", "user:admin", Set.of("admin"), Set.of("google", "password")
        );
        IdentityProperties.User participant = new IdentityProperties.User(
            "alice@example.test", "user:alice", Set.of("participants"), Set.of("google")
        );
        IdentityProperties properties = new IdentityProperties(
            PROPERTIES.issuer(),
            PROPERTIES.signingKeyLocation(),
            Set.of("admin", "participants"),
            Map.of("administrator", administrator, "alice", participant),
            PROPERTIES.grafana(),
            PROPERTIES.grafanaApi(),
            PROPERTIES.localLogin(),
            InteractiveClientGroupPolicyTest.properties().clients()
        );
        var context = context(
            AuthorizationGrantType.AUTHORIZATION_CODE,
            OAuth2TokenType.ACCESS_TOKEN,
            "restricted-client",
            IdentityTestAuthentications.google("alice@example.test")
        );

        new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties).customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getSubject()).isEqualTo("user:alice");
        assertThat(claims.getClaimAsStringList("groups")).containsExactly("participants");
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("participants");
        assertThat(claims.getClaimAsString("email")).isEqualTo("alice@example.test");
    }

    @Test
    void keepsMachineAuthenticationAsClientIdentity() {
        var context = context(
            AuthorizationGrantType.CLIENT_CREDENTIALS,
            OAuth2TokenType.ACCESS_TOKEN,
            "grafana-api",
            "grafana-api"
        );

        new AuthorizationServerConfiguration().jwtClaimsCustomizer(PROPERTIES).customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getSubject()).isEqualTo("grafana-api");
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("service");
        assertThat(claims.getClaims()).doesNotContainKey("email");
        assertThat(claims.getAudience()).containsExactly("grafana");
    }

    @Test
    void usesAudienceDeclaredForNewClient() {
        var portal = new IdentityProperties.ClientDefinition(
            "portal",
            "secret",
            Set.of("https://portal.example.test/callback"),
            Set.of("openid", "email", "profile"),
            Set.of("authorization_code"),
            Set.of("client_secret_basic"),
            "portal",
            true,
            null
        );
        var properties = new IdentityProperties(
            PROPERTIES.issuer(),
            PROPERTIES.allowedGoogleEmail(),
            PROPERTIES.canonicalUserId(),
            PROPERTIES.signingKeyLocation(),
            PROPERTIES.grafana(),
            PROPERTIES.grafanaApi(),
            PROPERTIES.localLogin(),
            Map.of("portal", portal)
        );
        var context = context(
            AuthorizationGrantType.AUTHORIZATION_CODE,
            new OAuth2TokenType("access_token"),
            "portal",
            "admin@example.test"
        );

        new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties).customize(context);

        assertThat(context.getClaims().build().getAudience()).containsExactly("portal");
    }

    @Test
    void idTokenUsesClientAudienceInsteadOfItsResourceAudience() {
        var client = new IdentityProperties.ClientDefinition("dashboard-ui", "test-secret",
            Set.of("https://example.test/callback"), Set.of("openid", "email"), Set.of("authorization_code"),
            Set.of("client_secret_basic"), "metrics-api", true, null);
        var properties = new IdentityProperties(PROPERTIES.issuer(), PROPERTIES.signingKeyLocation(), PROPERTIES.groups(),
            PROPERTIES.users(), PROPERTIES.grafana(), PROPERTIES.grafanaApi(), PROPERTIES.localLogin(), Map.of("dashboard", client));
        var context = context(AuthorizationGrantType.AUTHORIZATION_CODE, new OAuth2TokenType("id_token"),
            "dashboard-ui", "admin@example.test");
        context.getClaims().audience(java.util.List.of("dashboard-ui"));

        new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties).customize(context);

        assertThat(context.getClaims().build().getAudience()).containsExactly("dashboard-ui");
        assertThat(context.getClaims().build().getClaimAsBoolean("email_verified")).isTrue();
        var accessContext = context(AuthorizationGrantType.AUTHORIZATION_CODE, OAuth2TokenType.ACCESS_TOKEN,
            "dashboard-ui", "admin@example.test");
        new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties).customize(accessContext);
        assertThat(accessContext.getClaims().build().getAudience()).containsExactly("metrics-api");
    }

    @Test
    void refusesInteractiveTokensForUnknownAuthenticationMethod() {
        var context = context(AuthorizationGrantType.AUTHORIZATION_CODE, OAuth2TokenType.ACCESS_TOKEN, "grafana",
            new TestingAuthenticationToken("admin@example.test", "ignored", "ROLE_ADMIN"));

        assertThatThrownBy(() -> new AuthorizationServerConfiguration().jwtClaimsCustomizer(PROPERTIES).customize(context))
            .isInstanceOf(OAuth2AuthenticationException.class);
    }

    static JwtEncodingContext context(
        AuthorizationGrantType grantType,
        OAuth2TokenType tokenType,
        String clientId,
        String principalName) {
        Authentication principal = AuthorizationGrantType.CLIENT_CREDENTIALS.equals(grantType)
            ? new TestingAuthenticationToken(principalName, "ignored", "ROLE_SERVICE")
            : IdentityTestAuthentications.password(principalName);
        return context(grantType, tokenType, clientId, principal);
    }

    static JwtEncodingContext context(
        AuthorizationGrantType grantType,
        OAuth2TokenType tokenType,
        String clientId,
        Authentication principal) {
        RegisteredClient client = RegisteredClient
            .withId("registered-" + clientId)
            .clientId(clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(grantType)
            .redirectUri("https://grafana.example.test/login/generic_oauth")
            .build();
        return JwtEncodingContext
            .with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder())
            .registeredClient(client)
            .principal(principal)
            .tokenType(tokenType)
            .authorizationGrantType(grantType)
            .authorizedScopes(Set.of("openid"))
            .build();
    }
}
