package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginPageControllerTest {
  private static final IdentityProperties BASE = new IdentityProperties(
      "https://auth.calcifer.tech", "dem.gianluigi@gmail.com", "user:admin", "file:key",
      new IdentityProperties.Client("grafana", "secret", "https://grafana.calcifer.tech/login/generic_oauth", "grafana"),
      new IdentityProperties.Client("grafana-api", "secret", "https://grafana.calcifer.tech", "grafana"),
      new IdentityProperties.LocalLogin(false, "dem.gianluigi@gmail.com", ""));

  @Test
  void alwaysOffersGoogleAndHidesPasswordUntilEnabled() {
    String page = new LoginPageController(BASE).login(null);
    assertThat(page).contains("/oauth2/authorization/google");
    assertThat(page).doesNotContain("type=\"password\"");
  }

  @Test
  void offersPasswordFallbackWhenEnabled() {
    IdentityProperties properties = new IdentityProperties(BASE.issuer(), BASE.allowedGoogleEmail(), BASE.canonicalUserId(),
        BASE.signingKeyLocation(), BASE.grafana(), BASE.grafanaApi(),
        new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", "{bcrypt}hash"));
    String page = new LoginPageController(properties).login(null);
    assertThat(page).contains("action=\"/login\"").contains("type=\"password\"");
  }
}
