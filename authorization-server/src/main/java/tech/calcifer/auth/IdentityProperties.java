package tech.calcifer.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.validation.annotation.Validated;


@Validated
@ConfigurationProperties("identity")
public record IdentityProperties(
    @NotBlank String issuer,
    @NotBlank String signingKeyLocation,
    @NotEmpty Set<@NotBlank String> groups,
    @NotEmpty Map<@NotBlank String, @Valid User> users,
    @NotNull @Valid Client grafana,
    @NotNull @Valid Client grafanaApi,
    @NotNull @Valid LocalLogin localLogin,
    Map<@NotBlank String, @Valid ClientDefinition> clients
) {

    @ConstructorBinding
    public IdentityProperties {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        users = users == null ? Map.of() : Map.copyOf(users);
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    /** Compatibility constructor for callers that still describe the original administrator directly. */
    public IdentityProperties(
        String issuer,
        String allowedGoogleEmail,
        String canonicalUserId,
        String signingKeyLocation,
        Client grafana,
        Client grafanaApi,
        LocalLogin localLogin) {
        this(
            issuer,
            signingKeyLocation,
            Set.of("admin"),
            Map.of("admin", legacyAdministrator(allowedGoogleEmail, canonicalUserId, localLogin)),
            grafana,
            grafanaApi,
            localLogin,
            Map.of()
        );
    }

    /** Compatibility constructor for existing tests and callers that also provide clients. */
    public IdentityProperties(
        String issuer,
        String allowedGoogleEmail,
        String canonicalUserId,
        String signingKeyLocation,
        Client grafana,
        Client grafanaApi,
        LocalLogin localLogin,
        Map<String, ClientDefinition> clients) {
        this(
            issuer,
            signingKeyLocation,
            Set.of("admin"),
            Map.of("admin", legacyAdministrator(allowedGoogleEmail, canonicalUserId, localLogin)),
            grafana,
            grafanaApi,
            localLogin,
            clients
        );
    }

    @AssertTrue(message = "identity catalog contains invalid users, groups, methods, or client policies")
    public boolean isCatalogConfigurationValid() {
        if (groups.isEmpty()
            || users.isEmpty()
            || !groups.contains("admin")
            || groups.stream().anyMatch(group -> group == null || !group.matches("[a-z][a-z0-9-]*"))) {
            return false;
        }
        Set<String> subjects = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (Map.Entry<String, User> entry : users.entrySet()) {
            User user = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isBlank()
                || user == null
                || user.email() == null || user.email().isBlank()
                || user.canonicalSubject() == null || user.canonicalSubject().isBlank()
                || !subjects.add(user.canonicalSubject())
                || !emails.add(user.email().toLowerCase(java.util.Locale.ROOT))
                || user.groups().isEmpty()
                || !groups.containsAll(user.groups())
                || user.authenticationMethods().isEmpty()
                || !Set.of("google", "password").containsAll(user.authenticationMethods())
                || user.roles().stream().anyMatch(role -> !role.matches("[a-z][a-z0-9-]*"))
                || (user.roles().contains("admin") && !user.groups().contains("admin"))) {
                return false;
            }
        }
        for (ClientDefinition client : clients.values()) {
            if (client == null || !groups.containsAll(client.allowedGroups())
                || !subjects.containsAll(client.allowedSubjects())
                || ("rage-quit".equals(client.id()) && isPlaceholderSecret(client.secret()))
                || (client.requiredAuthenticationMethod() != null
                    && !Set.of("google", "password").contains(client.requiredAuthenticationMethod()))
                || ((!client.allowedGroups().isEmpty() || !client.allowedSubjects().isEmpty()
                    || client.requiredAuthenticationMethod() != null)
                    && !client.grantTypes().contains("authorization_code"))) {
                return false;
            }
        }
        if (localLogin == null) {
            return false;
        }
        if (localLogin.enabled()) {
            User passwordUser = userByEmail(localLogin.username());
            return passwordUser != null && passwordUser.authenticationMethods().contains("password");
        }
        return true;
    }

    public User userByEmail(String email) {
        if (email == null) {
            return null;
        }
        return users.values().stream()
            .filter(user -> user.email().equalsIgnoreCase(email))
            .findFirst()
            .orElse(null);
    }

    User userFor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (authentication instanceof OAuth2AuthenticationToken oauth2
            && "google".equals(oauth2.getAuthorizedClientRegistrationId())
            && principal instanceof OidcUser oidcUser
            && Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            User user = userByEmail(oidcUser.getEmail());
            return user != null && user.authenticationMethods().contains("google") ? user : null;
        }
        if (authentication instanceof UsernamePasswordAuthenticationToken
            && principal instanceof UserDetails localUser
            && localLogin.enabled()
            && localLogin.username().equalsIgnoreCase(localUser.getUsername())) {
            User user = userByEmail(localUser.getUsername());
            return user != null && user.authenticationMethods().contains("password") ? user : null;
        }
        // Unknown or legacy principal shapes require a fresh login, never inferred eligibility.
        return null;
    }

    String authenticationMethodFor(Authentication authentication) {
        if (userFor(authentication) == null) {
            return null;
        }
        return authentication instanceof OAuth2AuthenticationToken ? "google" : "password";
    }

    /** Legacy accessors retained for integrations that read the administrator identity. */
    public String allowedGoogleEmail() {
        return users.values().stream()
            .filter(user -> user.groups().contains("admin"))
            .map(User::email)
            .findFirst()
            .orElse("");
    }

    public String canonicalUserId() {
        return users.values().stream()
            .filter(user -> user.groups().contains("admin"))
            .map(User::canonicalSubject)
            .findFirst()
            .orElse("");
    }

    List<ClientDefinition> configuredClients() {
        List<ClientDefinition> definitions = new ArrayList<>();
        definitions.add(ClientDefinition.browser(grafana));
        definitions.add(ClientDefinition.machine(grafanaApi));
        definitions.addAll(clients.values());
        return List.copyOf(definitions);
    }

    String audienceFor(String clientId) {
        return configuredClients()
            .stream()
            .filter(client -> client.id().equals(clientId))
            .map(ClientDefinition::effectiveAudience)
            .findFirst()
            .orElse(clientId);
    }

    private static boolean isPlaceholderSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return true;
        }
        String normalized = secret.strip().toLowerCase(java.util.Locale.ROOT)
            .replace('-', '_').replace(' ', '_');
        return normalized.equals("replace") || normalized.startsWith("replace_")
            || normalized.equals("changeme") || normalized.startsWith("change_me")
            || normalized.equals("placeholder") || normalized.startsWith("placeholder_")
            || normalized.equals("example") || normalized.startsWith("example_");
    }

    public record Client(
        @NotBlank String id,
        @NotBlank String secret,
        @NotBlank String redirectUri,
        @NotBlank String audience
    ) {}

    public record User(
        @NotBlank @Email String email,
        @NotBlank String canonicalSubject,
        @NotEmpty Set<@NotBlank String> groups,
        @NotEmpty Set<@NotBlank String> authenticationMethods,
        Set<@NotBlank String> roles
    ) {
        @ConstructorBinding
        public User {
            groups = groups == null ? Set.of() : Set.copyOf(groups);
            authenticationMethods = authenticationMethods == null ? Set.of() : Set.copyOf(authenticationMethods);
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        public User(String email, String canonicalSubject, Set<String> groups, Set<String> authenticationMethods) {
            this(email, canonicalSubject, groups, authenticationMethods, Set.of());
        }

        Set<String> effectiveRoles() {
            return roles.isEmpty() ? groups : roles;
        }
    }

    public record ClientDefinition(
        @NotBlank String id,
        @NotBlank String secret,
        Set<@NotBlank String> redirectUris,
        @NotEmpty Set<@NotBlank String> scopes,
        @NotEmpty Set<@NotBlank String> grantTypes,
        Set<@NotBlank String> authenticationMethods,
        String audience,
        boolean requireProofKey,
        Duration accessTokenTtl,
        Set<@NotBlank String> allowedGroups,
        Set<@NotBlank String> allowedSubjects,
        String requiredAuthenticationMethod
    ) {

        @ConstructorBinding
        public ClientDefinition {
            redirectUris = redirectUris == null ? Set.of() : Set.copyOf(redirectUris);
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            grantTypes = grantTypes == null ? Set.of() : Set.copyOf(grantTypes);
            authenticationMethods = authenticationMethods == null || authenticationMethods.isEmpty() ? Set.of(
                "client_secret_basic") : Set.copyOf(authenticationMethods);
            allowedGroups = allowedGroups == null ? Set.of() : Set.copyOf(allowedGroups);
            allowedSubjects = allowedSubjects == null ? Set.of() : Set.copyOf(allowedSubjects);
        }

        public ClientDefinition(
            String id, String secret, Set<String> redirectUris, Set<String> scopes, Set<String> grantTypes,
            Set<String> authenticationMethods, String audience, boolean requireProofKey, Duration accessTokenTtl,
            Set<String> allowedGroups) {
            this(id, secret, redirectUris, scopes, grantTypes, authenticationMethods, audience, requireProofKey,
                accessTokenTtl, allowedGroups, Set.of(), null);
        }

        public ClientDefinition(
            String id,
            String secret,
            Set<String> redirectUris,
            Set<String> scopes,
            Set<String> grantTypes,
            Set<String> authenticationMethods,
            String audience,
            boolean requireProofKey,
            Duration accessTokenTtl) {
            this(
                id,
                secret,
                redirectUris,
                scopes,
                grantTypes,
                authenticationMethods,
                audience,
                requireProofKey,
                accessTokenTtl,
                Set.of()
            );
        }

        @AssertTrue(message = "authorization_code clients require at least one redirect URI")
        public boolean isAuthorizationCodeConfigurationValid() {
            return !grantTypes.contains("authorization_code") || !redirectUris.isEmpty();
        }

        String effectiveAudience() {
            return audience == null || audience.isBlank() ? id : audience;
        }

        Set<String> effectiveAllowedGroups() {
            if (!grantTypes.contains("authorization_code")) {
                return Set.of();
            }
            // An explicit subject allowlist replaces the legacy admin default, not an explicit group restriction.
            return allowedGroups.isEmpty() && allowedSubjects.isEmpty() ? Set.of("admin") : allowedGroups;
        }

        static ClientDefinition browser(Client client) {
            return new ClientDefinition(
                client.id(),
                client.secret(),
                Set.of(client.redirectUri()),
                Set.of("openid", "profile", "email"),
                Set.of("authorization_code"),
                Set.of("client_secret_basic"),
                client.audience(),
                true,
                null,
                Set.of("admin")
            );
        }

        static ClientDefinition machine(Client client) {
            return new ClientDefinition(
                client.id(),
                client.secret(),
                Set.of(),
                Set.of("grafana.api"),
                Set.of("client_credentials"),
                Set.of("client_secret_basic"),
                client.audience(),
                false,
                null,
                Set.of()
            );
        }
    }

    public record LocalLogin(
        boolean enabled,
        @NotBlank String username,
        String passwordHash
    ) {}

    private static User legacyAdministrator(String email, String subject, LocalLogin localLogin) {
        Set<String> methods = localLogin != null && localLogin.enabled()
            ? Set.of("google", "password")
            : Set.of("google");
        return new User(email, subject, Set.of("admin"), methods);
    }
}
