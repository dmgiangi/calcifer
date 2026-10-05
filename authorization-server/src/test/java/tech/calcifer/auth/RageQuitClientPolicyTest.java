package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

class RageQuitClientPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"dem.gianluigi@gmail.com", "pugliens@gmail.com", "frevadiscor@gmail.com"})
    void allThreeGoogleIdentitiesHaveRealSubjectsRolesAndVerifiedEmails(String email) {
        var properties = RageQuitTestSupport.properties();
        var principal = RageQuitTestSupport.google(email);
        assertThat(new InteractiveClientGroupPolicy(properties).allows("rage-quit", principal)).isTrue();
        for (String type : Set.of("access_token", "id_token")) {
            var context = AuthorizationClaimsCustomizerTest.context(AuthorizationGrantType.AUTHORIZATION_CODE,
                new OAuth2TokenType(type), "rage-quit", principal);
            new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties).customize(context);
            var claims = context.getClaims().build();
            assertThat(claims.getSubject()).isEqualTo(RageQuitTestSupport.SUBJECTS.get(email));
            assertThat(claims.getAudience()).containsExactly("rage-quit");
            assertThat(claims.getClaimAsString("email")).isEqualTo(email);
            assertThat(claims.getClaimAsBoolean("email_verified")).isTrue();
            assertThat(claims.getClaimAsStringList("roles"))
                .containsExactly(email.equals(RageQuitTestSupport.ADMIN) ? "admin" : "rage-quit-user");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pugliens@gmail.com", "frevadiscor@gmail.com"})
    void participantsCannotAuthorizeOrIssueAnyOtherClientTokens(String email) {
        var properties = RageQuitTestSupport.properties();
        for (String client : Set.of("grafana", "homepage", "home-assistant", "zigbee2mqtt", "grafana-api", "unknown")) {
            assertThat(new InteractiveClientGroupPolicy(properties).allows(client, RageQuitTestSupport.google(email)))
                .as(client).isFalse();
            for (String type : Set.of("access_token", "id_token")) {
                var context = AuthorizationClaimsCustomizerTest.context(AuthorizationGrantType.AUTHORIZATION_CODE,
                    new OAuth2TokenType(type), client, RageQuitTestSupport.google(email));
                assertThatThrownBy(() -> new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties)
                    .customize(context)).isInstanceOf(OAuth2AuthenticationException.class);
            }
        }
    }

    @Test
    void trustedAuthenticationSourceIsNotAnEmailOrAuthorityClaim() {
        var policy = new InteractiveClientGroupPolicy(RageQuitTestSupport.properties());
        assertThat(policy.allows("rage-quit", RageQuitTestSupport.password())).isFalse();
        assertThat(policy.requiresGoogleAuthentication("rage-quit", RageQuitTestSupport.password())).isTrue();
        assertThat(policy.allows("grafana", RageQuitTestSupport.password())).isTrue();
        assertThat(policy.allows("rage-quit", new TestingAuthenticationToken(RageQuitTestSupport.ADMIN, "ignored",
            "ROLE_ADMIN", "FACTOR_AUTHORIZATION_CODE"))).isFalse();
        assertThat(policy.allows("rage-quit", IdentityTestAuthentications.google(RageQuitTestSupport.ADMIN, false, "google")))
            .isFalse();
        assertThat(policy.allows("rage-quit", IdentityTestAuthentications.google(RageQuitTestSupport.ADMIN, true, "other")))
            .isFalse();
        assertThat(policy.allows("rage-quit", RageQuitTestSupport.google("unknown@example.test"))).isFalse();
        assertThat(policy.allows("rage-quit", RageQuitTestSupport.google("PUGLIENS@gmail.com"))).isTrue();
    }

    @Test
    void subjectAndGroupRestrictionsAreBothRequiredWhenExplicit() {
        var restricted = definition(Set.of("admin"), Set.of("user:moody"), "google");
        var policy = new InteractiveClientGroupPolicy(RageQuitTestSupport.withClient(restricted));
        assertThat(policy.allows("restricted", RageQuitTestSupport.google("pugliens@gmail.com"))).isFalse();
        assertThat(policy.allows("restricted", RageQuitTestSupport.google(RageQuitTestSupport.ADMIN))).isFalse();
        var groupOnly = new InteractiveClientGroupPolicy(RageQuitTestSupport.withClient(
            definition(Set.of("rage-quit"), Set.of(), null)));
        assertThat(groupOnly.allows("restricted", RageQuitTestSupport.google("pugliens@gmail.com"))).isTrue();
    }

    @Test
    void unknownSubjectsMethodsAndMachinePoliciesFailValidation() {
        assertThat(RageQuitTestSupport.properties().isCatalogConfigurationValid()).isTrue();
        assertThat(RageQuitTestSupport.withClient(definition(Set.of(), Set.of("user:missing"), "google"))
            .isCatalogConfigurationValid()).isFalse();
        assertThat(RageQuitTestSupport.withClient(definition(Set.of(), Set.of("user:admin"), "magic"))
            .isCatalogConfigurationValid()).isFalse();
        var machine = new IdentityProperties.ClientDefinition("machine", "test-secret", Set.of(), Set.of("metrics"),
            Set.of("client_credentials"), Set.of("client_secret_basic"), "metrics", false, null,
            Set.of(), Set.of("user:admin"), "google");
        assertThat(RageQuitTestSupport.withClient(machine).isCatalogConfigurationValid()).isFalse();
    }

    private static IdentityProperties.ClientDefinition definition(Set<String> groups, Set<String> subjects, String method) {
        return new IdentityProperties.ClientDefinition("restricted", "test-secret", Set.of("https://example.test/callback"),
            Set.of("openid"), Set.of("authorization_code"), Set.of("client_secret_basic"), "restricted", true, null,
            groups, subjects, method);
    }
}
