package tech.calcifer.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;


@Validated
@ConfigurationProperties("identity")
public record IdentityProperties(
    @NotBlank String issuer,
    @NotBlank String allowedGoogleEmail,
    @NotBlank String canonicalUserId,
    @NotBlank String signingKeyLocation,
    @Valid Client grafana,
    @Valid Client grafanaApi,
    @Valid LocalLogin localLogin,
    @Valid Map<String, @Valid ClientDefinition> clients
) {

    @ConstructorBinding
    public IdentityProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

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
            allowedGoogleEmail,
            canonicalUserId,
            signingKeyLocation,
            grafana,
            grafanaApi,
            localLogin,
            Map.of()
        );
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

    public record Client(
        @NotBlank String id,
        @NotBlank String secret,
        @NotBlank String redirectUri,
        @NotBlank String audience
    ) {}

    public record ClientDefinition(
        @NotBlank String id,
        @NotBlank String secret,
        Set<@NotBlank String> redirectUris,
        @NotEmpty Set<@NotBlank String> scopes,
        @NotEmpty Set<@NotBlank String> grantTypes,
        Set<@NotBlank String> authenticationMethods,
        String audience,
        boolean requireProofKey,
        Duration accessTokenTtl
    ) {

        public ClientDefinition {
            redirectUris = redirectUris == null ? Set.of() : Set.copyOf(redirectUris);
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            grantTypes = grantTypes == null ? Set.of() : Set.copyOf(grantTypes);
            authenticationMethods = authenticationMethods == null || authenticationMethods.isEmpty() ? Set.of(
                "client_secret_basic") : Set.copyOf(authenticationMethods);
        }

        @AssertTrue(message = "authorization_code clients require at least one redirect URI")
        public boolean isAuthorizationCodeConfigurationValid() {
            return !grantTypes.contains("authorization_code") || !redirectUris.isEmpty();
        }

        String effectiveAudience() {
            return audience == null || audience.isBlank() ? id : audience;
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
                null
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
                null
            );
        }
    }

    public record LocalLogin(
        boolean enabled,
        @NotBlank String username,
        String passwordHash
    ) {}
}
