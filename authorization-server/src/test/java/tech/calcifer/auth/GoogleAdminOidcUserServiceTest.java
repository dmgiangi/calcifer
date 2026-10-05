package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
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

    @Test
    void mapsGoogleOnlyUserToCatalogAuthoritiesWithoutAdminRole() {
        IdentityProperties properties = propertiesWithGoogleOnlyUser();
        @SuppressWarnings("unchecked") OAuth2UserService<OidcUserRequest, OidcUser> delegate
            = mock(OAuth2UserService.class);
        OidcUser user = mock(OidcUser.class);
        Instant issuedAt = Instant.parse("2026-09-19T23:23:00Z");
        OidcIdToken idToken = OidcIdToken
            .withTokenValue("google-id-token")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .subject("google-pugliens")
            .build();
        when(user.getEmail()).thenReturn("pugliens@gmail.com");
        when(user.getEmailVerified()).thenReturn(true);
        when(user.getIdToken()).thenReturn(idToken);
        when(delegate.loadUser(null)).thenReturn(user);

        OidcUser loaded = new GoogleAdminOidcUserService(delegate, properties).loadUser(null);

        assertThat(loaded.getName()).isEqualTo("google-pugliens");
        assertThat(loaded.getAuthorities()).extracting(authority -> authority.getAuthority())
            .contains("ROLE_RAGE_QUIT", FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
            .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void rejectsGoogleWhenCatalogUserDoesNotAllowThatMethod() {
        IdentityProperties base = properties();
        IdentityProperties.User passwordOnly = new IdentityProperties.User(
            "dem.gianluigi@gmail.com", "user:admin", Set.of("admin"), Set.of("password")
        );
        IdentityProperties properties = new IdentityProperties(
            base.issuer(), base.signingKeyLocation(), Set.of("admin"), java.util.Map.of("administrator", passwordOnly),
            base.grafana(), base.grafanaApi(), base.localLogin(), java.util.Map.of()
        );
        @SuppressWarnings("unchecked") OAuth2UserService<OidcUserRequest, OidcUser> delegate
            = mock(OAuth2UserService.class);
        OidcUser user = mock(OidcUser.class);
        when(user.getEmail()).thenReturn("dem.gianluigi@gmail.com");
        when(user.getEmailVerified()).thenReturn(true);
        when(delegate.loadUser(null)).thenReturn(user);

        assertThatThrownBy(() -> new GoogleAdminOidcUserService(delegate, properties).loadUser(null))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Google identity is not authorized");
    }

    private static IdentityProperties propertiesWithGoogleOnlyUser() {
        IdentityProperties base = properties();
        return new IdentityProperties(
            base.issuer(),
            base.signingKeyLocation(),
            Set.of("admin", "rage-quit"),
            java.util.Map.of(
                "administrator", new IdentityProperties.User(
                    "dem.gianluigi@gmail.com", "user:admin", Set.of("admin"), Set.of("google", "password")
                ),
                "pugliens", new IdentityProperties.User(
                    "pugliens@gmail.com", "user:pugliens", Set.of("rage-quit"), Set.of("google")
                )
            ),
            base.grafana(),
            base.grafanaApi(),
            base.localLogin(),
            java.util.Map.of()
        );
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
