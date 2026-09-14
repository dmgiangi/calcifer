package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class RegisteredClientConfigurationTest {
  private static final IdentityProperties PROPERTIES = new IdentityProperties(
      "https://auth.calcifer.tech", "dem.gianluigi@gmail.com", "user:admin", "file:key",
      new IdentityProperties.Client("grafana", "secret", "https://grafana.calcifer.tech/login/generic_oauth", "grafana"),
      new IdentityProperties.Client("grafana-api", "secret", "https://grafana.calcifer.tech", "grafana"),
      new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", "{bcrypt}hash"));

  @Test
  void exposesOnlyTheRegisteredClientAndScopes() {
    var repository = new AuthorizationServerConfiguration().registeredClientRepository(
        PROPERTIES, PasswordEncoderFactories.createDelegatingPasswordEncoder());

    var browser = repository.findByClientId("grafana");
    var machine = repository.findByClientId("grafana-api");

    assertThat(repository.findByClientId("unknown")).isNull();
    assertThat(browser.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    assertThat(machine.getScopes()).containsExactly("grafana.api");
    assertThat(browser.getScopes()).doesNotContain("grafana.api");
  }
}
