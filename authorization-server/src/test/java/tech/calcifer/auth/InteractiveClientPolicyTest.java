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

class InteractiveClientPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"admin@example.test", "alice@example.test", "bob@example.test"})
    void verifiedGoogleIdentitiesHaveCanonicalSubjectsAndConfiguredRoles(String email) {
        var properties = InteractiveClientTestSupport.properties();
        var principal = InteractiveClientTestSupport.google(email);
        assertThat(new InteractiveClientGroupPolicy(properties).allows("restricted-client", principal)).isTrue();
        for (String type : Set.of("access_token", "id_token")) {
            var context = AuthorizationClaimsCustomizerTest.context(AuthorizationGrantType.AUTHORIZATION_CODE,
                new OAuth2TokenType(type), "restricted-client", principal);
            new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties).customize(context);
            var claims = context.getClaims().build();
            assertThat(claims.getSubject()).isEqualTo(InteractiveClientTestSupport.SUBJECTS.get(email));
            assertThat(claims.getAudience()).containsExactly("restricted-client");
            assertThat(claims.getClaimAsString("email")).isEqualTo(email);
            assertThat(claims.getClaimAsBoolean("email_verified")).isTrue();
            assertThat(claims.getClaimAsStringList("roles"))
                .containsExactly(email.equals(InteractiveClientTestSupport.ADMIN) ? "admin" : "participant");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.test", "bob@example.test"})
    void participantsCannotAuthorizeOrIssueAnyOtherClientTokens(String email) {
        var properties = InteractiveClientTestSupport.properties();
        for (String client : Set.of("grafana", "admin-console", "admin-reports", "admin-settings", "grafana-api", "unknown")) {
            assertThat(new InteractiveClientGroupPolicy(properties).allows(client, InteractiveClientTestSupport.google(email)))
                .as(client).isFalse();
            for (String type : Set.of("access_token", "id_token")) {
                var context = AuthorizationClaimsCustomizerTest.context(AuthorizationGrantType.AUTHORIZATION_CODE,
                    new OAuth2TokenType(type), client, InteractiveClientTestSupport.google(email));
                assertThatThrownBy(() -> new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties)
                    .customize(context)).isInstanceOf(OAuth2AuthenticationException.class);
            }
        }
    }

    @Test
    void trustedAuthenticationSourceIsNotAnEmailOrAuthorityClaim() {
        var policy = new InteractiveClientGroupPolicy(InteractiveClientTestSupport.properties());
        assertThat(policy.allows("restricted-client", InteractiveClientTestSupport.password())).isFalse();
        assertThat(policy.requiresGoogleAuthentication("restricted-client", InteractiveClientTestSupport.password())).isTrue();
        assertThat(policy.allows("grafana", InteractiveClientTestSupport.password())).isTrue();
        assertThat(policy.allows("restricted-client", new TestingAuthenticationToken(InteractiveClientTestSupport.ADMIN, "ignored",
            "ROLE_ADMIN", "FACTOR_AUTHORIZATION_CODE"))).isFalse();
        assertThat(policy.allows("restricted-client", IdentityTestAuthentications.google(InteractiveClientTestSupport.ADMIN, false, "google")))
            .isFalse();
        assertThat(policy.allows("restricted-client", IdentityTestAuthentications.google(InteractiveClientTestSupport.ADMIN, true, "other")))
            .isFalse();
        assertThat(policy.allows("restricted-client", InteractiveClientTestSupport.google("unknown@example.test"))).isFalse();
        assertThat(policy.allows("restricted-client", InteractiveClientTestSupport.google("ALICE@example.test"))).isTrue();
    }

    @Test
    void subjectAndGroupRestrictionsAreBothRequiredWhenExplicit() {
        var restricted = definition(Set.of("admin"), Set.of("user:alice"), "google");
        var policy = new InteractiveClientGroupPolicy(InteractiveClientTestSupport.withClient(restricted));
        assertThat(policy.allows("restricted", InteractiveClientTestSupport.google("alice@example.test"))).isFalse();
        assertThat(policy.allows("restricted", InteractiveClientTestSupport.google(InteractiveClientTestSupport.ADMIN))).isFalse();
        var groupOnly = new InteractiveClientGroupPolicy(InteractiveClientTestSupport.withClient(
            definition(Set.of("participants"), Set.of(), null)));
        assertThat(groupOnly.allows("restricted", InteractiveClientTestSupport.google("alice@example.test"))).isTrue();
        var subjectOnly = new InteractiveClientGroupPolicy(InteractiveClientTestSupport.withClient(
            definition(Set.of(), Set.of("user:alice"), "google")));
        assertThat(subjectOnly.allows("restricted", InteractiveClientTestSupport.google("alice@example.test"))).isTrue();
        assertThat(subjectOnly.allows("restricted", InteractiveClientTestSupport.google("bob@example.test"))).isFalse();
        assertThat(subjectOnly.allows("restricted", InteractiveClientTestSupport.google(InteractiveClientTestSupport.ADMIN))).isFalse();
    }

    @Test
    void unknownSubjectsMethodsAndMachinePoliciesFailValidation() {
        assertThat(InteractiveClientTestSupport.properties().isCatalogConfigurationValid()).isTrue();
        assertThat(InteractiveClientTestSupport.withClient(definition(Set.of(), Set.of("user:missing"), "google"))
            .isCatalogConfigurationValid()).isFalse();
        assertThat(InteractiveClientTestSupport.withClient(definition(Set.of(), Set.of("user:admin"), "magic"))
            .isCatalogConfigurationValid()).isFalse();
        var machine = new IdentityProperties.ClientDefinition("machine", "test-secret", Set.of(), Set.of("metrics"),
            Set.of("client_credentials"), Set.of("client_secret_basic"), "metrics", false, null,
            Set.of(), Set.of("user:admin"), "google");
        assertThat(InteractiveClientTestSupport.withClient(machine).isCatalogConfigurationValid()).isFalse();
    }

    private static IdentityProperties.ClientDefinition definition(Set<String> groups, Set<String> subjects, String method) {
        return new IdentityProperties.ClientDefinition("restricted", "test-secret", Set.of("https://example.test/callback"),
            Set.of("openid"), Set.of("authorization_code"), Set.of("client_secret_basic"), "restricted", true, null,
            groups, subjects, method);
    }
}