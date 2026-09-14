package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class AuthorizationStateLocalityTest {
  @Test
  void authorizationStateIsNotSharedBetweenIdentityInstances() {
    RegisteredClient client = RegisteredClient.withId("client-registration")
        .clientId("grafana")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://grafana.calcifer.tech/login/generic_oauth")
        .build();
    OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
        .id("authorization-code-state")
        .principalName("user:admin")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizedScopes(Set.of("openid"))
        .attribute("oauth2-authorization-request", "home-edge")
        .build();

    InMemoryOAuth2AuthorizationService home = new InMemoryOAuth2AuthorizationService();
    InMemoryOAuth2AuthorizationService cloud = new InMemoryOAuth2AuthorizationService();
    home.save(authorization);

    assertThat(home.findById("authorization-code-state")).isNotNull();
    assertThat(cloud.findById("authorization-code-state")).isNull();
  }
}
