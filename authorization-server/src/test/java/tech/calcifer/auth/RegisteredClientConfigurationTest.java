package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import tech.calcifer.auth.state.AuthorizationStateProperties;

class RegisteredClientConfigurationTest {
  private static final AuthorizationStateProperties STATE = new AuthorizationStateProperties(false,
      AuthorizationStateProperties.Role.CLOUD,
      new AuthorizationStateProperties.Redis("localhost", 6379, "", "", "auth"), 3,
      Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(30),
      Duration.ofSeconds(20), Duration.ofSeconds(3), 100, Duration.ofMinutes(5));
  private static final IdentityProperties PROPERTIES = new IdentityProperties(
      "https://auth.calcifer.tech", "dem.gianluigi@gmail.com", "user:admin", "file:key",
      new IdentityProperties.Client("grafana", "secret", "https://grafana.calcifer.tech/login/generic_oauth", "grafana"),
      new IdentityProperties.Client("grafana-api", "secret", "https://grafana.calcifer.tech", "grafana"),
      new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", "{bcrypt}hash"));

  @Test
  void exposesOnlyTheRegisteredClientAndScopes() {
    var repository = new AuthorizationServerConfiguration().registeredClientRepository(
        PROPERTIES, PasswordEncoderFactories.createDelegatingPasswordEncoder(), STATE);

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
    assertThat(yaml).contains("redirect-uri: https://auth.calcifer.tech/login/oauth2/code/google")
        .doesNotContain("auth-cloud.calcifer.tech", "auth-home.calcifer.tech");
  }
}
