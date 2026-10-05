package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/** Package bridge lets Redis tests check package-private production policy without widening its API. */
public final class RageQuitRestoredPolicyAssertions {

    private RageQuitRestoredPolicyAssertions() {}

    public static void verify(Authentication restored, boolean password) {
        var properties = RageQuitTestSupport.properties();
        var policy = new InteractiveClientGroupPolicy(properties);
        assertThat(policy.allows("rage-quit", restored)).isEqualTo(!password);
        assertThat(properties.authenticationMethodFor(restored)).isEqualTo(password ? "password" : "google");
        var user = properties.userFor(restored);
        assertThat(user).isNotNull();
        assertThat(user.canonicalSubject()).isEqualTo(RageQuitTestSupport.SUBJECTS.get(user.email()));
        if (!user.groups().contains("admin")) {
            assertThat(user.effectiveRoles()).containsExactly("rage-quit-user");
            for (String client : java.util.Set.of("grafana", "homepage", "home-assistant", "zigbee2mqtt", "grafana-api", "unknown")) {
                assertThat(policy.allows(client, restored)).isFalse();
                assertThatThrownBy(() -> new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties)
                    .customize(AuthorizationClaimsCustomizerTest.context(AuthorizationGrantType.AUTHORIZATION_CODE,
                        OAuth2TokenType.ACCESS_TOKEN, client, restored))).isInstanceOf(OAuth2AuthenticationException.class);
            }
        }
    }
}