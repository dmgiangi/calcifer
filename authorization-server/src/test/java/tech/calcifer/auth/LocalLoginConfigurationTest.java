package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class LocalLoginConfigurationTest {
  private static final IdentityProperties.Client GRAFANA =
      new IdentityProperties.Client("grafana", "secret", "https://grafana.calcifer.tech/login/generic_oauth", "grafana");
  private static final IdentityProperties.Client API =
      new IdentityProperties.Client("grafana-api", "secret", "https://grafana.calcifer.tech", "grafana");

  @Test
  void refusesEnabledLocalLoginWithoutHash() {
    IdentityProperties properties = properties("");
    assertThatThrownBy(() -> new AuthorizationServerConfiguration().localAdministrator(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Local login requires AUTH_LOCAL_LOGIN_PASSWORD_HASH");
  }

  @Test
  void acceptsBcryptHashWithoutExposingPlaintext() {
    String hash = new BCryptPasswordEncoder().encode("test-password");
    var service = new AuthorizationServerConfiguration().localAdministrator(properties(hash));
    assertThat(service.loadUserByUsername("dem.gianluigi@gmail.com").getPassword()).isEqualTo(hash);
  }

  @Test
  void rejectsBadPassword() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(new AuthorizationServerConfiguration()
        .localAdministrator(properties(encoder.encode("correct-password"))));
    provider.setPasswordEncoder(encoder);
    assertThatThrownBy(() -> provider.authenticate(
        new UsernamePasswordAuthenticationToken("dem.gianluigi@gmail.com", "wrong-password")))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void explicitLocalProviderAuthenticatesTheConfiguredUser() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    var configuration = new AuthorizationServerConfiguration();
    var provider = configuration.localPasswordAuthenticationProvider(
        properties(encoder.encode("correct-password")), encoder);

    var authentication = provider.authenticate(
        new UsernamePasswordAuthenticationToken("dem.gianluigi@gmail.com", "correct-password"));

    assertThat(authentication.isAuthenticated()).isTrue();
    assertThat(authentication.getName()).isEqualTo("dem.gianluigi@gmail.com");
    assertThat(authentication.getAuthorities()).extracting(Object::toString).contains("ROLE_ADMIN");
  }

  private static IdentityProperties properties(String hash) {
    return new IdentityProperties("https://auth.calcifer.tech", "dem.gianluigi@gmail.com", "user:admin", "file:key",
        GRAFANA, API, new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", hash));
  }
}
