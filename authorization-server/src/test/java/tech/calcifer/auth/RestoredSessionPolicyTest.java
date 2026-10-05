package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;


class RestoredSessionPolicyTest {

    private static final String ADMIN = "dem.gianluigi@gmail.com";

    @ParameterizedTest
    @ValueSource(strings = {"google", "password"})
    void restoredSessionsRemainEligibleWhenTheirMethodIsStillAllowed(String method) throws Exception {
        Authentication restored = restored(authentication(method));
        var properties = InteractiveClientGroupPolicyTest.properties();
        assertThat(properties.userFor(restored).canonicalSubject()).isEqualTo("user:admin");
        assertThat(new InteractiveClientGroupPolicy(properties).allows("grafana", restored)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "password"})
    void restoredSessionsCannotAuthorizeOrIssueTokensAfterTheirMethodIsRevoked(String method) throws Exception {
        Authentication restored = restored(authentication(method));
        var original = InteractiveClientGroupPolicyTest.properties();
        assertThat(new InteractiveClientGroupPolicy(original).allows("grafana", restored)).isTrue();
        var changed = withAdministratorMethods(Set.of(method.equals("google") ? "password" : "google"));
        var policy = new InteractiveClientGroupPolicy(changed);
        assertThat(changed.isCatalogConfigurationValid()).isTrue();
        assertThat(changed.userFor(restored)).isNull();
        assertThat(policy.allows("grafana", restored)).isFalse();

        var request = new OAuth2AuthorizationCodeRequestAuthenticationToken(
            "https://auth.calcifer.tech/oauth2/authorize",
            "grafana",
            restored,
            "https://grafana.calcifer.tech/login/generic_oauth",
            "state",
            Set.of("openid"),
            Map.of()
        );
        var tokenContext = AuthorizationClaimsCustomizerTest.context(
            AuthorizationGrantType.AUTHORIZATION_CODE,
            OAuth2TokenType.ACCESS_TOKEN,
            "grafana",
            restored
        );
        var authorizationContext = OAuth2AuthorizationCodeRequestAuthenticationContext
            .with(request)
            .registeredClient(tokenContext.getRegisteredClient())
            .build();
        assertThatThrownBy(() -> policy.validate(authorizationContext)).isInstanceOf(
            OAuth2AuthorizationCodeRequestAuthenticationException.class);

        for (String tokenType : Set.of("access_token", "id_token")) {
            var context = AuthorizationClaimsCustomizerTest.context(
                AuthorizationGrantType.AUTHORIZATION_CODE,
                new OAuth2TokenType(tokenType),
                "grafana",
                restored
            );
            assertThatThrownBy(() -> new AuthorizationServerConfiguration()
                .jwtClaimsCustomizer(changed)
                .customize(context)).isInstanceOf(OAuth2AuthenticationException.class);
        }
    }

    @Test
    void rejectsUnknownProviderUnverifiedIdentityAndUnsupportedPrincipalTypes() {
        var properties = InteractiveClientGroupPolicyTest.properties();
        assertThat(properties.userFor(IdentityTestAuthentications.google(ADMIN, true, "other-provider"))).isNull();
        assertThat(properties.userFor(IdentityTestAuthentications.google(ADMIN, false, "google"))).isNull();
        assertThat(properties.userFor(new TestingAuthenticationToken(ADMIN, "ignored", "ROLE_ADMIN"))).isNull();
        assertThat(properties.userFor(new TestingAuthenticationToken(ADMIN, "ignored"))).isNull();
        assertThat(properties.userFor(IdentityTestAuthentications.password("pugliens@gmail.com"))).isNull();
    }

    @Test
    void disablingLocalLoginInvalidatesExistingPasswordSessions() {
        var original = InteractiveClientGroupPolicyTest.properties();
        var disabled = new IdentityProperties(
            original.issuer(),
            original.signingKeyLocation(),
            original.groups(),
            original.users(),
            original.grafana(),
            original.grafanaApi(),
            new IdentityProperties.LocalLogin(false, ADMIN, ""),
            original.clients()
        );
        assertThat(disabled.userFor(IdentityTestAuthentications.password(ADMIN))).isNull();
        assertThat(disabled.userFor(IdentityTestAuthentications.google(ADMIN))).isNotNull();
    }

    private static IdentityProperties withAdministratorMethods(Set<String> methods) {
        var original = InteractiveClientGroupPolicyTest.properties();
        var users = new java.util.HashMap<>(original.users());
        var admin = users.get("administrator");
        users.put(
            "administrator",
            new IdentityProperties.User(admin.email(), admin.canonicalSubject(), admin.groups(), methods)
        );
        return new IdentityProperties(
            original.issuer(),
            original.signingKeyLocation(),
            original.groups(),
            users,
            original.grafana(),
            original.grafanaApi(),
            new IdentityProperties.LocalLogin(methods.contains("password"), ADMIN, "{bcrypt}unused-test-hash"),
            original.clients()
        );
    }

    private static Authentication authentication(String method) {
        return method.equals("google") ? IdentityTestAuthentications.google(ADMIN)
            : IdentityTestAuthentications.password(ADMIN);
    }

    private static Authentication restored(Authentication authentication) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(new SecurityContextImpl(authentication));
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return ((SecurityContextImpl) input.readObject()).getAuthentication();
        }
    }
}