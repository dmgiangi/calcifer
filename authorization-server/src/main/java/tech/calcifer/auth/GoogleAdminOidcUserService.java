package tech.calcifer.auth;

import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.stereotype.Component;


@Component
class GoogleAdminOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final IdentityProperties properties;

    @Autowired
    GoogleAdminOidcUserService(IdentityProperties properties) {
        this(new OidcUserService(), properties);
    }

    GoogleAdminOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, IdentityProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = delegate.loadUser(request);
        String email = user.getEmail();
        IdentityProperties.User configuredUser = properties.userByEmail(email);
        if (configuredUser == null
            || !configuredUser.authenticationMethods().contains("google")
            || !Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new AccessDeniedException("Google identity is not authorized");
        }
        Set<GrantedAuthority> authorities = new java.util.HashSet<>();
        configuredUser.groups().stream().map(GoogleAdminOidcUserService::roleAuthority).map(SimpleGrantedAuthority::new)
            .forEach(authorities::add);
        authorities.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY));
        return new DefaultOidcUser(
            authorities,
            user.getIdToken(),
            user.getUserInfo(),
            "sub"
        );
    }

    private static String roleAuthority(String group) {
        return "ROLE_" + group.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }
}
