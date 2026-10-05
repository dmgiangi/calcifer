package tech.calcifer.auth;

import java.security.Principal;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;

/** Rechecks persisted provenance before the framework exchanges a code or invokes a token generator. */
final class ClientPolicyAuthorizationCodeProvider implements AuthenticationProvider {

    private final AuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;
    private final InteractiveClientGroupPolicy policy;

    ClientPolicyAuthorizationCodeProvider(AuthenticationProvider delegate, OAuth2AuthorizationService authorizations,
        IdentityProperties properties) {
        this.delegate = delegate;
        this.authorizations = authorizations;
        this.policy = new InteractiveClientGroupPolicy(properties);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var request = (OAuth2AuthorizationCodeAuthenticationToken) authentication;
        if (request.getPrincipal() instanceof OAuth2ClientAuthenticationToken client && client.isAuthenticated()
            && client.getRegisteredClient() != null) {
            var authorization = authorizations.findByToken(request.getCode(), new OAuth2TokenType("code"));
            if (authorization != null) {
                Object principal = authorization.getAttribute(Principal.class.getName());
                if (!(principal instanceof Authentication user)
                    || !policy.allows(client.getRegisteredClient().getClientId(), user)) {
                    throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
                }
            }
        }
        // Keep the standard client, callback, expiry, PKCE and one-time-code validation intact.
        return delegate.authenticate(authentication);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
