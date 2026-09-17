package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import tech.calcifer.auth.state.AuthorizationStateProperties;


class RegisteredClientConfigurationTest {

    private static final AuthorizationStateProperties STATE = new AuthorizationStateProperties(
        false,
        AuthorizationStateProperties.Role.CLOUD,
        new AuthorizationStateProperties.Redis("localhost", 6379, "", "", "auth"),
        3,
        Duration.ofSeconds(2),
        Duration.ofSeconds(15),
        Duration.ofSeconds(30),
        Duration.ofSeconds(30),
        Duration.ofSeconds(20),
        Duration.ofSeconds(3),
        100,
        Duration.ofMinutes(5)
    );
    private static final IdentityProperties PROPERTIES = new IdentityProperties(
        "https://auth.calcifer.tech",
        "dem.gianluigi@gmail.com",
        "user:admin",
        "file:key",
        new IdentityProperties.Client(
            "grafana",
            "secret",
            "https://grafana.calcifer.tech/login/generic_oauth",
            "grafana"
        ),
        new IdentityProperties.Client("grafana-api", "secret", "https://grafana.calcifer.tech", "grafana"),
        new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", "{bcrypt}hash")
    );

    @Test
    void exposesOnlyTheRegisteredClientAndScopes() {
        var repository = new AuthorizationServerConfiguration().registeredClientRepository(
            PROPERTIES,
            PasswordEncoderFactories.createDelegatingPasswordEncoder(),
            STATE
        );

        var browser = repository.findByClientId("grafana");
        var machine = repository.findByClientId("grafana-api");

        assertThat(repository.findByClientId("unknown")).isNull();
        assertThat(browser.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(machine.getScopes()).containsExactly("grafana.api");
        assertThat(browser.getScopes()).doesNotContain("grafana.api");
        assertThat(browser.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(machine.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(machine.getAuthorizationGrantTypes()).doesNotContain(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(browser.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(5));
        assertThat(machine.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(5));
        assertThat(browser.getAuthorizationGrantTypes()).doesNotContain(AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(browser.getScopes()).doesNotContain("offline_access");
    }

    @Test
    void configuresOnlyTheCanonicalGoogleCallback() throws Exception {
        String yaml = new ClassPathResource("application.yaml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(yaml)
            .contains("redirect-uri: https://auth.calcifer.tech/login/oauth2/code/google")
            .doesNotContain("auth-cloud.calcifer.tech", "auth-home.calcifer.tech");
    }

    @Test
    void registersDeclarativeAuthorizationCodeAndMachineClients() {
        var homepage = new IdentityProperties.ClientDefinition(
            "homepage",
            "homepage-secret",
            Set.of("https://calcifer.tech/api/auth/callback/homepage-oidc"),
            Set.of("openid", "profile", "email"),
            Set.of("authorization_code"),
            Set.of("client_secret_post"),
            "homepage",
            true,
            Duration.ofMinutes(2)
        );
        var exporter = new IdentityProperties.ClientDefinition(
            "exporter",
            "exporter-secret",
            Set.of(),
            Set.of("metrics.write"),
            Set.of("client_credentials"),
            Set.of("client_secret_basic"),
            "metrics",
            false,
            null
        );
        var properties = withClients(Map.of("homepage", homepage, "exporter", exporter));

        var repository = new AuthorizationServerConfiguration().registeredClientRepository(
            properties,
            PasswordEncoderFactories.createDelegatingPasswordEncoder(),
            STATE
        );

        var browser = repository.findByClientId("homepage");
        var machine = repository.findByClientId("exporter");
        assertThat(browser.getRedirectUris()).containsExactly("https://calcifer.tech/api/auth/callback/homepage-oidc");
        assertThat(browser.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(browser.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(browser.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(2));
        assertThat(machine.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(machine.getRedirectUris()).isEmpty();
        assertThat(machine.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void registersHomeAssistantAsConfidentialPkceClient() {
        var homeAssistant = new IdentityProperties.ClientDefinition(
            "home-assistant",
            "home-assistant-secret",
            Set.of("https://home.calcifer.tech/auth/oidc/callback"),
            Set.of("openid", "profile", "email"),
            Set.of("authorization_code"),
            Set.of("client_secret_post"),
            "home-assistant",
            true,
            null
        );
        var repository = new AuthorizationServerConfiguration().registeredClientRepository(
            withClients(Map.of("home-assistant", homeAssistant)),
            PasswordEncoderFactories.createDelegatingPasswordEncoder(),
            STATE
        );

        var client = repository.findByClientId("home-assistant");

        assertThat(client.getRedirectUris()).containsExactly("https://home.calcifer.tech/auth/oidc/callback");
        assertThat(client.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
    }

    @Test
    void rejectsUnresolvedSecretsAndInvalidDefinitions() {
        var unresolved = new IdentityProperties.ClientDefinition(
            "homepage",
            "${HOMEPAGE_OIDC_CLIENT_SECRET}",
            Set.of("https://calcifer.tech/callback"),
            Set.of("openid"),
            Set.of("authorization_code"),
            Set.of("client_secret_basic"),
            "homepage",
            true,
            null
        );
        var missingRedirect = new IdentityProperties.ClientDefinition(
            "broken",
            "secret",
            Set.of(),
            Set.of("openid"),
            Set.of("authorization_code"),
            Set.of("client_secret_basic"),
            "broken",
            true,
            null
        );

        assertThatThrownBy(() -> repository(withClients(Map.of("homepage", unresolved))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be resolved");
        assertThatThrownBy(() -> repository(withClients(Map.of("broken", missingRedirect))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires redirect URI");
    }

    private static IdentityProperties withClients(Map<String, IdentityProperties.ClientDefinition> clients) {
        return new IdentityProperties(
            PROPERTIES.issuer(),
            PROPERTIES.allowedGoogleEmail(),
            PROPERTIES.canonicalUserId(),
            PROPERTIES.signingKeyLocation(),
            PROPERTIES.grafana(),
            PROPERTIES.grafanaApi(),
            PROPERTIES.localLogin(),
            clients
        );
    }

    private static Object repository(IdentityProperties properties) {
        return new AuthorizationServerConfiguration().registeredClientRepository(
            properties,
            PasswordEncoderFactories.createDelegatingPasswordEncoder(),
            STATE
        );
    }
}
