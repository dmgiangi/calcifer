package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;


class GoogleAdminOidcUserServiceTest {

    @Test
    void addsAuthorizationCodeFactorToVerifiedGoogleIdentity() {
        IdentityProperties properties = properties();
        @SuppressWarnings("unchecked") OAuth2UserService<OidcUserRequest, OidcUser> delegate
            = mock(OAuth2UserService.class);
        OidcUser user = mock(OidcUser.class);
        Instant issuedAt = Instant.parse("2026-09-19T23:23:00Z");
        OidcIdToken idToken = OidcIdToken
            .withTokenValue("google-id-token")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .subject("google-user")
            .build();
        when(user.getEmail()).thenReturn("dem.gianluigi@gmail.com");
        when(user.getEmailVerified()).thenReturn(true);
        when(user.getIdToken()).thenReturn(idToken);
        when(delegate.loadUser(null)).thenReturn(user);

        OidcUser loaded = new GoogleAdminOidcUserService(delegate, properties).loadUser(null);

        assertThat(loaded.getAuthorities())
            .extracting(authority -> authority.getAuthority())
            .containsExactlyInAnyOrder("ROLE_ADMIN", FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
        assertThat(loaded.getAuthorities()).anyMatch(FactorGrantedAuthority.class::isInstance);
    }

    @Test
    void rejectsVerifiedGoogleIdentityWithAnotherEmail() {
        IdentityProperties properties = properties();
        @SuppressWarnings("unchecked") OAuth2UserService<OidcUserRequest, OidcUser> delegate
            = mock(OAuth2UserService.class);
        OidcUser user = mock(OidcUser.class);
        when(user.getEmail()).thenReturn("other@example.com");
        when(user.getEmailVerified()).thenReturn(true);
        when(delegate.loadUser(null)).thenReturn(user);

        assertThatThrownBy(() -> new GoogleAdminOidcUserService(delegate, properties).loadUser(null))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Google identity is not authorized");
    }

    @Test
    void rejectsUnverifiedConfiguredGoogleIdentity() {
        IdentityProperties properties = properties();
        @SuppressWarnings("unchecked") OAuth2UserService<OidcUserRequest, OidcUser> delegate
            = mock(OAuth2UserService.class);
        OidcUser user = mock(OidcUser.class);
        when(user.getEmail()).thenReturn("dem.gianluigi@gmail.com");
        when(user.getEmailVerified()).thenReturn(false);
        when(delegate.loadUser(null)).thenReturn(user);

        assertThatThrownBy(() -> new GoogleAdminOidcUserService(delegate, properties).loadUser(null))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Google identity is not authorized");
    }

    private static IdentityProperties properties() {
        return new IdentityProperties(
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
    }
}
