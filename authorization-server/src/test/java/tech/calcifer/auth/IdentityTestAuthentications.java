package tech.calcifer.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;


final class IdentityTestAuthentications {

    private IdentityTestAuthentications() {
    }

    static UsernamePasswordAuthenticationToken password(String email) {
        var principal = User.withUsername(email).password("unused-test-hash").roles("ADMIN").build();
        List<GrantedAuthority> authorities = new ArrayList<>(principal.getAuthorities());
        authorities.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
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
        // Mirror the timestamped factor supplied by the production Google user service.
        var factor = FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
            .issuedAt(now).build();
        var principal = new DefaultOidcUser(List.of(factor), token);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), registration);
    }
}