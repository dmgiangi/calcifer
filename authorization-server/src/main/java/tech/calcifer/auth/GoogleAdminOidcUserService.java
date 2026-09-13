package tech.calcifer.auth;

import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.stereotype.Component;

@Component
class GoogleAdminOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  private final OidcUserService delegate = new OidcUserService();
  private final IdentityProperties properties;

  GoogleAdminOidcUserService(IdentityProperties properties) { this.properties = properties; }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    OidcUser user = delegate.loadUser(request);
    String email = user.getEmail();
    if (!properties.allowedGoogleEmail().equalsIgnoreCase(email) || !Boolean.TRUE.equals(user.getEmailVerified())) {
      throw new AccessDeniedException("Google identity is not authorized");
    }
    return new DefaultOidcUser(Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")), user.getIdToken(), user.getUserInfo(), "sub");
  }
}
