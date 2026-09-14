package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;

class GoogleAdminOidcUserServiceTest {
  @Test
  void rejectsVerifiedGoogleIdentityWithAnotherEmail() {
    IdentityProperties properties = new IdentityProperties(
        "https://auth.calcifer.tech", "dem.gianluigi@gmail.com", "user:admin", "file:key",
        new IdentityProperties.Client("grafana", "secret", "https://grafana.calcifer.tech/login/generic_oauth", "grafana"),
        new IdentityProperties.Client("grafana-api", "secret", "https://grafana.calcifer.tech", "grafana"),
        new IdentityProperties.LocalLogin(false, "dem.gianluigi@gmail.com", ""));
    @SuppressWarnings("unchecked")
    OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
    OidcUser user = mock(OidcUser.class);
    when(user.getEmail()).thenReturn("other@example.com");
    when(user.getEmailVerified()).thenReturn(true);
    when(delegate.loadUser(null)).thenReturn(user);

    assertThatThrownBy(() -> new GoogleAdminOidcUserService(delegate, properties).loadUser(null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Google identity is not authorized");
  }
}
