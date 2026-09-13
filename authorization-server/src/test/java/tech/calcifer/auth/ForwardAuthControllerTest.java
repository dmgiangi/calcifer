package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class ForwardAuthControllerTest {
  private static final IdentityProperties PROPERTIES = new IdentityProperties("https://auth.calcifer.tech", "dem.gianluigi@gmail.com", "file:key",
      new IdentityProperties.Client("grafana", "secret", "https://grafana.calcifer.tech/login/generic_oauth"),
      new IdentityProperties.Client("grafana-api", "secret", "https://grafana.calcifer.tech"),
      new IdentityProperties.LocalLogin(false, "dem.gianluigi@gmail.com", ""));

  @Test
  void allowsBrowserRequestWithoutBearer() {
    var controller = new ForwardAuthController(Mockito.mock(JwtDecoder.class), PROPERTIES);
    assertThat(controller.authenticate(null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void addsTrustedHeadersForScopedMachineToken() {
    JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
    Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"),
        Map.of("iss", "https://auth.calcifer.tech", "aud", List.of("grafana"), "scope", "grafana.api", "sub", "grafana-api"));
    when(decoder.decode("token")).thenReturn(jwt);
    var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getHeaders().getFirst("X-WEBAUTH-USER")).isEqualTo("service:grafana-api");
  }
}
