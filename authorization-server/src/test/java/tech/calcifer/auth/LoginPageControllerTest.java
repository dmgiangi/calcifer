package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;


class LoginPageControllerTest {

    private static final IdentityProperties BASE = new IdentityProperties(
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
        new IdentityProperties.LocalLogin(false, "dem.gianluigi@gmail.com", "")
    );

    @Test
    void alwaysOffersGoogleAndHidesPasswordUntilEnabled() {
        var controller = new LoginPageController(BASE);
        assertThat(controller.root(null)).isEqualTo("redirect:/login");
        assertThat(controller.login(null)).isEqualTo("forward:/login.html");
        assertThat(controller.loginConfig()).containsEntry("passwordEnabled", false);
    }

    @Test
    void authenticatedCanonicalRootAndLoginLeadToSessionPage() {
        var controller = new LoginPageController(BASE);
        var authentication = new TestingAuthenticationToken("user:admin", "ignored", "ROLE_ADMIN");
        authentication.setAuthenticated(true);

        assertThat(controller.root(authentication)).isEqualTo("redirect:/session");
        assertThat(controller.login(authentication)).isEqualTo("redirect:/session");
        assertThat(controller.session()).isEqualTo("forward:/session.html");
    }

    @Test
    void offersPasswordFallbackWhenEnabled() {
        IdentityProperties properties = new IdentityProperties(
            BASE.issuer(),
            BASE.allowedGoogleEmail(),
            BASE.canonicalUserId(),
            BASE.signingKeyLocation(),
            BASE.grafana(),
            BASE.grafanaApi(),
            new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", "{bcrypt}hash")
        );
        assertThat(new LoginPageController(properties).loginConfig()).containsEntry("passwordEnabled", true);
    }
}
