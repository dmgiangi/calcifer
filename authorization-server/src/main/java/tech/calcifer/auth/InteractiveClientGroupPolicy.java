package tech.calcifer.auth;

import java.util.Collections;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;


final class InteractiveClientGroupPolicy {

    private final IdentityProperties properties;

    InteractiveClientGroupPolicy(IdentityProperties properties) {
        this.properties = properties;
    }

    void validate(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        Authentication principal = (Authentication) request.getPrincipal();
        if (principal == null || !principal.isAuthenticated() || principal instanceof AnonymousAuthenticationToken) {
            return;
        }
        if (!allows(context.getRegisteredClient().getClientId(), principal)) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED, "User is not allowed to authorize this client", null),
                request
            );
        }
    }

    boolean allows(String clientId, Authentication authentication) {
        IdentityProperties.User user = properties.userFor(authentication);
        IdentityProperties.ClientDefinition client = client(clientId);
        return user != null
            && client != null
            && permitsIdentity(client, user)
            && (client.requiredAuthenticationMethod() == null
                || client.requiredAuthenticationMethod().equals(properties.authenticationMethodFor(authentication)));
    }

    boolean requiresGoogleAuthentication(String clientId, Authentication authentication) {
        IdentityProperties.ClientDefinition client = client(clientId);
        IdentityProperties.User user = properties.userFor(authentication);
        return client != null && "google".equals(client.requiredAuthenticationMethod())
            && (user == null || (permitsIdentity(client, user) && user.authenticationMethods().contains("google")))
            && !"google".equals(properties.authenticationMethodFor(authentication));
    }

    private boolean permitsIdentity(IdentityProperties.ClientDefinition client, IdentityProperties.User user) {
        return client.grantTypes().contains("authorization_code")
            && (client.effectiveAllowedGroups().isEmpty()
                || !Collections.disjoint(user.groups(), client.effectiveAllowedGroups()))
            && (client.allowedSubjects().isEmpty() || client.allowedSubjects().contains(user.canonicalSubject()));
    }

    private IdentityProperties.ClientDefinition client(String clientId) {
        return properties.configuredClients().stream()
            .filter(definition -> definition.id().equals(clientId)).findFirst().orElse(null);
    }
}
