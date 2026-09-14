package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

class AuthorizationClaimsCustomizerTest {
  private static final IdentityProperties PROPERTIES = new IdentityProperties(
      "https://auth.calcifer.tech", "dem.gianluigi@gmail.com", "user:admin", "file:key",
      new IdentityProperties.Client("grafana", "secret", "https://grafana.calcifer.tech/login/generic_oauth", "grafana"),
      new IdentityProperties.Client("grafana-api", "secret", "https://grafana.calcifer.tech", "grafana"),
      new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", "{bcrypt}hash"));

  @Test
  void mapsInteractiveAuthenticationToCanonicalSubject() {
    var context = context(AuthorizationGrantType.AUTHORIZATION_CODE,
        new OAuth2TokenType("access_token"), "grafana", "google-sub");

    new AuthorizationServerConfiguration().jwtClaimsCustomizer(PROPERTIES).customize(context);

    JwtClaimsSet claims = context.getClaims().build();
    assertThat(claims.getSubject()).isEqualTo("user:admin");
    assertThat(claims.getClaimAsString("email")).isEqualTo("dem.gianluigi@gmail.com");
    assertThat(claims.getClaimAsStringList("roles")).containsExactly("admin");
    assertThat(claims.getAudience()).containsExactly("grafana");
  }

  @Test
  void keepsMachineAuthenticationAsClientIdentity() {
    var context = context(AuthorizationGrantType.CLIENT_CREDENTIALS,
        OAuth2TokenType.ACCESS_TOKEN, "grafana-api", "grafana-api");

    new AuthorizationServerConfiguration().jwtClaimsCustomizer(PROPERTIES).customize(context);

    JwtClaimsSet claims = context.getClaims().build();
    assertThat(claims.getSubject()).isEqualTo("grafana-api");
    assertThat(claims.getClaimAsStringList("roles")).containsExactly("service");
    assertThat(claims.getClaims()).doesNotContainKey("email");
    assertThat(claims.getAudience()).containsExactly("grafana");
  }

  private static JwtEncodingContext context(AuthorizationGrantType grantType, OAuth2TokenType tokenType,
      String clientId, String principalName) {
    RegisteredClient client = RegisteredClient.withId("registered-" + clientId)
        .clientId(clientId)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(grantType)
        .redirectUri("https://grafana.calcifer.tech/login/generic_oauth")
        .build();
    return JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder())
        .registeredClient(client)
        .principal(new TestingAuthenticationToken(principalName, "credentials"))
        .tokenType(tokenType)
        .authorizationGrantType(grantType)
        .authorizedScopes(Set.of("openid"))
        .build();
  }
}
