package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;


class GoogleAdminOidcUserServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"admin@example.test", "alice@example.test", "bob@example.test"})
    void admitsTheThreeVerifiedParticipantsWithOnlyTheirConfiguredRoles(String email) {
        @SuppressWarnings("unchecked") OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
        var source = (OidcUser) InteractiveClientTestSupport.google(email).getPrincipal();
        when(delegate.loadUser(null)).thenReturn(source);
        var loaded = new GoogleAdminOidcUserService(delegate, InteractiveClientTestSupport.properties()).loadUser(null);
        assertThat(loaded.getAuthorities()).extracting(authority -> authority.getAuthority())
            .containsExactlyInAnyOrder(email.equals(InteractiveClientTestSupport.ADMIN) ? "ROLE_ADMIN" : "ROLE_PARTICIPANT",
                FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
        var authentication = new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
            loaded, loaded.getAuthorities(), "google");
        assertThat(InteractiveClientTestSupport.properties().userFor(authentication).canonicalSubject())
            .isEqualTo(InteractiveClientTestSupport.SUBJECTS.get(email));
    }

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
        when(user.getEmail()).thenReturn("admin@example.test");
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
        when(user.getEmail()).thenReturn("other@example.test");
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
        when(user.getEmail()).thenReturn("admin@example.test");
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
            .subject("google-alice")
            .build();
        when(user.getEmail()).thenReturn("alice@example.test");
        when(user.getEmailVerified()).thenReturn(true);
        when(user.getIdToken()).thenReturn(idToken);
        when(delegate.loadUser(null)).thenReturn(user);

        OidcUser loaded = new GoogleAdminOidcUserService(delegate, properties).loadUser(null);

        assertThat(loaded.getName()).isEqualTo("google-alice");
        assertThat(loaded.getAuthorities()).extracting(authority -> authority.getAuthority())
            .contains("ROLE_PARTICIPANTS", FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
            .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void rejectsGoogleWhenCatalogUserDoesNotAllowThatMethod() {
        IdentityProperties base = properties();
        IdentityProperties.User passwordOnly = new IdentityProperties.User(
            "admin@example.test", "user:admin", Set.of("admin"), Set.of("password")
        );
        IdentityProperties properties = new IdentityProperties(
            base.issuer(), base.signingKeyLocation(), Set.of("admin"), java.util.Map.of("administrator", passwordOnly),
            base.grafana(), base.grafanaApi(), base.localLogin(), java.util.Map.of()
        );
        @SuppressWarnings("unchecked") OAuth2UserService<OidcUserRequest, OidcUser> delegate
            = mock(OAuth2UserService.class);
        OidcUser user = mock(OidcUser.class);
        when(user.getEmail()).thenReturn("admin@example.test");
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
            Set.of("admin", "participants"),
            java.util.Map.of(
                "administrator", new IdentityProperties.User(
                    "admin@example.test", "user:admin", Set.of("admin"), Set.of("google", "password")
                ),
                "alice", new IdentityProperties.User(
                    "alice@example.test", "user:alice", Set.of("participants"), Set.of("google")
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
            "https://auth.example.test",
            "admin@example.test",
            "user:admin",
            "file:key",
            new IdentityProperties.Client(
                "grafana",
                "secret",
                "https://grafana.example.test/login/generic_oauth",
                "grafana"
            ),
            new IdentityProperties.Client("grafana-api", "secret", "https://grafana.example.test", "grafana"),
            new IdentityProperties.LocalLogin(false, "admin@example.test", "")
        );
    }
}
