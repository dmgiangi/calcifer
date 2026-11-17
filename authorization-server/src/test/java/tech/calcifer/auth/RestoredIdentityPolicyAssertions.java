package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/** Package bridge lets Redis tests check package-private production policy without widening its API. */
public final class RestoredIdentityPolicyAssertions {

    private RestoredIdentityPolicyAssertions() {}

    public static void verify(Authentication restored, boolean password) {
        var properties = InteractiveClientTestSupport.properties();
        var policy = new InteractiveClientGroupPolicy(properties);
        assertThat(policy.allows("restricted-client", restored)).isEqualTo(!password);
        assertThat(properties.authenticationMethodFor(restored)).isEqualTo(password ? "password" : "google");
        var user = properties.userFor(restored);
        assertThat(user).isNotNull();
        assertThat(user.canonicalSubject()).isEqualTo(InteractiveClientTestSupport.SUBJECTS.get(user.email()));
        if (!user.groups().contains("admin")) {
            assertThat(user.effectiveRoles()).containsExactly("participant");
            for (String client : java.util.Set.of("grafana", "admin-console", "admin-reports", "admin-settings", "grafana-api", "unknown")) {
                assertThat(policy.allows(client, restored)).isFalse();
                assertThatThrownBy(() -> new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties)
                    .customize(AuthorizationClaimsCustomizerTest.context(AuthorizationGrantType.AUTHORIZATION_CODE,
                        OAuth2TokenType.ACCESS_TOKEN, client, restored))).isInstanceOf(OAuth2AuthenticationException.class);
            }
        }
    }
}