package tech.calcifer.auth;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;


/**
 * Adds current catalog membership without bypassing the framework's UserInfo token validation.
 */
final class CatalogOidcUserInfoMapper implements Function<OidcUserInfoAuthenticationContext, OidcUserInfo> {

    private static final Map<String, Set<String>> SCOPE_CLAIMS = Map.of(
        OidcScopes.EMAIL,
        Set.of("email", "email_verified"),
        OidcScopes.ADDRESS,
        Set.of("address"),
        OidcScopes.PHONE,
        Set.of("phone_number", "phone_number_verified"),
        OidcScopes.PROFILE,
        Set.of(
            "name",
            "family_name",
            "given_name",
            "middle_name",
            "nickname",
            "preferred_username",
            "profile",
            "picture",
            "website",
            "gender",
            "birthdate",
            "zoneinfo",
            "locale",
            "updated_at"
        )
    );

    private final IdentityProperties properties;

    CatalogOidcUserInfoMapper(IdentityProperties properties) {
        this.properties = properties;
    }

    @Override
    public OidcUserInfo apply(OidcUserInfoAuthenticationContext context) {
        Object principal = context.getAuthorization().getAttribute(Principal.class.getName());
        IdentityProperties.User user = principal instanceof Authentication authentication ? properties.userFor(
            authentication) : null;
        var authorizedIdToken = context.getAuthorization().getToken(OidcIdToken.class);
        if (user == null || authorizedIdToken == null || !user
            .canonicalSubject()
            .equals(authorizedIdToken.getToken().getSubject())) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_TOKEN);
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.canonicalSubject());
        Map<String, Object> issuedClaims = authorizedIdToken.getToken().getClaims();
        for (String scope : context.getAccessToken().getScopes()) {
            for (String claim : SCOPE_CLAIMS.getOrDefault(scope, Set.of())) {
                if (issuedClaims.containsKey(claim)) {
                    claims.put(claim, issuedClaims.get(claim));
                }
            }
        }
        if (context.getAccessToken().getScopes().contains(OidcScopes.EMAIL)) {
            claims.put("email", user.email());
        }
        claims.put("groups", user.groups());
        claims.put("roles", user.groups());
        return new OidcUserInfo(claims);
    }
}