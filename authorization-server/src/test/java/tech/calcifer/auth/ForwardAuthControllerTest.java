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
import org.springframework.security.oauth2.jwt.JwtException;


class ForwardAuthControllerTest {

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
        new IdentityProperties.LocalLogin(false, "dem.gianluigi@gmail.com", "")
    );

    @Test
    void allowsBrowserRequestWithoutBearer() {
        var controller = new ForwardAuthController(Mockito.mock(JwtDecoder.class), PROPERTIES);
        assertThat(controller.authenticate(null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void addsTrustedHeadersForScopedMachineToken() {
        JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of(
                "iss",
                "https://auth.calcifer.tech",
                "aud",
                List.of("grafana"),
                "scope",
                "grafana.api",
                "sub",
                "grafana-api"
            )
        );
        when(decoder.decode("token")).thenReturn(jwt);
        var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getFirst("X-WEBAUTH-USER")).isEqualTo("service:grafana-api");
    }

    @Test
    void acceptsListFormOfScopeClaim() {
        JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of(
                "iss",
                "https://auth.calcifer.tech",
                "aud",
                List.of("grafana"),
                "scope",
                List.of("grafana.api"),
                "sub",
                "grafana-api"
            )
        );
        when(decoder.decode("token")).thenReturn(jwt);
        var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void rejectsLookalikeScope() {
        JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of(
                "iss",
                "https://auth.calcifer.tech",
                "aud",
                List.of("grafana"),
                "scope",
                "not-grafana.api",
                "sub",
                "grafana-api"
            )
        );
        when(decoder.decode("token")).thenReturn(jwt);
        var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsInvalidScheme() {
        var controller = new ForwardAuthController(Mockito.mock(JwtDecoder.class), PROPERTIES);
        assertThat(controller.authenticate("Basic credentials").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsWrongIssuer() {
        JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of(
                "iss",
                "https://other.example",
                "aud",
                List.of("grafana"),
                "scope",
                "grafana.api",
                "sub",
                "grafana-api"
            )
        );
        when(decoder.decode("token")).thenReturn(jwt);
        var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsWrongAudience() {
        JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of(
                "iss",
                "https://auth.calcifer.tech",
                "aud",
                List.of("other"),
                "scope",
                "grafana.api",
                "sub",
                "grafana-api"
            )
        );
        when(decoder.decode("token")).thenReturn(jwt);
        var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsMissingScope() {
        JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of("iss", "https://auth.calcifer.tech", "aud", List.of("grafana"), "sub", "grafana-api")
        );
        when(decoder.decode("token")).thenReturn(jwt);
        var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsExpiredMachineToken() {
        JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            "token",
            Instant.now().minusSeconds(120),
            Instant.now().minusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of(
                "iss",
                "https://auth.calcifer.tech",
                "aud",
                List.of("grafana"),
                "scope",
                "grafana.api",
                "sub",
                "grafana-api"
            )
        );
        when(decoder.decode("token")).thenReturn(jwt);
        var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void translatesDecoderFailuresToUnauthorized() {
        JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
        when(decoder.decode("token")).thenThrow(new JwtException("expired"));
        var response = new ForwardAuthController(decoder, PROPERTIES).authenticate("Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
