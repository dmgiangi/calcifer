package tech.calcifer.auth;

import java.time.Instant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;


final class IdentityTestAuthentications {

    private IdentityTestAuthentications() {
    }

    static UsernamePasswordAuthenticationToken password(String email) {
        var principal = User.withUsername(email).password("unused-test-hash").roles("ADMIN").build();
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    static OAuth2AuthenticationToken google(String email) {
        return google(email, true, "google");
    }

    static OAuth2AuthenticationToken google(String email, boolean verified, String registration) {
        Instant now = Instant.now();
        var token = OidcIdToken
            .withTokenValue("unused-test-token")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .subject("google-provider-subject")
            .claim("email", email)
            .claim("email_verified", verified)
            .build();
        var principal = new DefaultOidcUser(java.util.List.of(), token);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), registration);
    }
}