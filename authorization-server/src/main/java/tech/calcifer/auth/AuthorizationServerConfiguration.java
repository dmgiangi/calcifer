package tech.calcifer.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import tech.calcifer.auth.state.AuthorizationStateProperties;


@Configuration
class AuthorizationServerConfiguration {

    @Bean
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http, IdentityProperties properties)
        throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http
            .securityMatcher(authorizationServer.getEndpointsMatcher())
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .with(
                authorizationServer,
                configurer -> configurer.oidc(oidc -> oidc.providerConfigurationEndpoint(endpoint -> endpoint.providerConfigurationCustomizer(
                    provider -> {
                        provider.grantTypes(grants -> {
                            grants.clear();
                            properties
                                .configuredClients()
                                .stream()
                                .flatMap(client -> client.grantTypes().stream())
                                .map(AuthorizationServerConfiguration::grantType)
                                .map(AuthorizationGrantType::getValue)
                                .forEach(grants::add);
                        });
                        provider.scopes(scopes -> {
                            scopes.clear();
                            properties
                                .configuredClients()
                                .stream()
                                .flatMap(client -> client.scopes().stream())
                                .forEach(scopes::add);
                        });
                    })))
            )
            .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
            .requestCache(cache -> cache.requestCache(authorizationRequestCache()))
            .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint(
                    "/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
            ));
        return http.build();
    }

    @Bean
    SecurityFilterChain applicationSecurityFilterChain(
        HttpSecurity http,
        IdentityProperties properties,
        GoogleAdminOidcUserService googleUserService,
        ObjectProvider<DaoAuthenticationProvider> localPasswordProvider,
        AuthenticationSuccessHandler oauth2LoginSuccessHandler) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize.requestMatchers(
                "/actuator/health/**",
                "/actuator/prometheus",
                "/internal/traefik/forward-auth",
                "/",
                "/error",
                "/login",
                "/login.html",
                "/login.css",
                "/login.js",
                "/google-mark.svg",
                "/login/config",
                "/login/csrf",
                "/oauth2/**",
                "/login/oauth2/**"
            ).permitAll().anyRequest().authenticated())
            .requestCache(cache -> cache.requestCache(authorizationRequestCache()))
            .oauth2Login(login -> login
                .loginPage("/login")
                .successHandler(oauth2LoginSuccessHandler)
                .userInfoEndpoint(endpoint -> endpoint.oidcUserService(googleUserService)))
            .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        if (properties.localLogin().enabled()) {
            // With multiple filter chains, do not rely on the global manager discovery
            // to attach the conditional local UserDetailsService to form login.
            http.authenticationProvider(localPasswordProvider.getObject());
            http.formLogin(form -> form.loginPage("/login").successHandler(oauth2LoginSuccessHandler).permitAll());
        }
        return http.build();
    }

    @Bean
    RequestCache authorizationRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(request -> "GET".equals(request.getMethod())
            && "/oauth2/authorize".equals(request.getRequestURI()));
        return cache;
    }

    @Bean
    AuthenticationSuccessHandler oauth2LoginSuccessHandler(RequestCache authorizationRequestCache) {
        SavedRequestAwareAuthenticationSuccessHandler handler = new SavedRequestAwareAuthenticationSuccessHandler();
        handler.setRequestCache(authorizationRequestCache);
        handler.setDefaultTargetUrl("/");
        return handler;
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(
        IdentityProperties properties,
        PasswordEncoder passwordEncoder,
        AuthorizationStateProperties stateProperties) {
        Set<String> clientIds = new HashSet<>();
        List<RegisteredClient> clients = properties
            .configuredClients()
            .stream()
            .map(client -> registeredClient(client, passwordEncoder, stateProperties.accessTokenTtl(), clientIds))
            .toList();
        return new InMemoryRegisteredClientRepository(clients);
    }

    private static RegisteredClient registeredClient(
        IdentityProperties.ClientDefinition definition,
        PasswordEncoder passwordEncoder,
        Duration defaultAccessTokenTtl,
        Set<String> clientIds) {
        validateClientDefinition(definition, clientIds);
        RegisteredClient.Builder builder = RegisteredClient
            .withId(UUID.randomUUID().toString())
            .clientId(definition.id())
            .clientSecret(passwordEncoder.encode(definition.secret()))
            .clientSettings(ClientSettings
                .builder()
                .requireProofKey(definition.requireProofKey())
                .requireAuthorizationConsent(false)
                .build())
            .tokenSettings(TokenSettings
                .builder()
                .accessTokenTimeToLive(
                    definition.accessTokenTtl() == null ? defaultAccessTokenTtl : definition.accessTokenTtl())
                .build());
        definition
            .authenticationMethods()
            .stream()
            .map(AuthorizationServerConfiguration::authenticationMethod)
            .forEach(builder::clientAuthenticationMethod);
        definition
            .grantTypes()
            .stream()
            .map(AuthorizationServerConfiguration::grantType)
            .forEach(builder::authorizationGrantType);
        definition.redirectUris().forEach(builder::redirectUri);
        definition.scopes().forEach(builder::scope);
        return builder.build();
    }

    private static void validateClientDefinition(
        IdentityProperties.ClientDefinition definition,
        Set<String> clientIds) {
        if (definition.id() == null || definition.id().isBlank()) {
            throw new IllegalArgumentException("OAuth client id must not be blank");
        }
        if (definition.secret() == null || definition.secret().isBlank() || definition.secret().startsWith("${")) {
            throw new IllegalArgumentException("OAuth client secret must be resolved for " + definition.id());
        }
        if (!clientIds.add(definition.id())) {
            throw new IllegalArgumentException("Duplicate OAuth client id: " + definition.id());
        }
        if (definition.grantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue()) && definition
            .redirectUris()
            .isEmpty()) {
            throw new IllegalArgumentException("authorization_code client requires redirect URI: " + definition.id());
        }
        if (definition.scopes().isEmpty() || definition.grantTypes().isEmpty()) {
            throw new IllegalArgumentException("OAuth client scopes and grant types must not be empty: "
                + definition.id());
        }
        if (definition.accessTokenTtl() != null && (definition.accessTokenTtl().isZero() || definition
            .accessTokenTtl()
            .isNegative())) {
            throw new IllegalArgumentException("OAuth client access token TTL must be positive: " + definition.id());
        }
    }

    private static AuthorizationGrantType grantType(String value) {
        return switch (value) {
            case "authorization_code" -> AuthorizationGrantType.AUTHORIZATION_CODE;
            case "client_credentials" -> AuthorizationGrantType.CLIENT_CREDENTIALS;
            case "refresh_token" -> AuthorizationGrantType.REFRESH_TOKEN;
            default -> throw new IllegalArgumentException("Unsupported OAuth grant type: " + value);
        };
    }

    private static ClientAuthenticationMethod authenticationMethod(String value) {
        return switch (value) {
            case "client_secret_basic" -> ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
            case "client_secret_post" -> ClientAuthenticationMethod.CLIENT_SECRET_POST;
            default -> throw new IllegalArgumentException("Unsupported OAuth client authentication method: " + value);
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "identity.state", name = "enabled", havingValue = "false", matchIfMissing = true)
    OAuth2AuthorizationService authorizationService() {
        // Authorization codes, consents, and browser sessions are deliberately
        // local to this process. The canonical issuer and JWTs are the shared v1
        // contract; a path change may require starting authentication again.
        return new InMemoryOAuth2AuthorizationService();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    UserDetailsService localAdministrator(IdentityProperties properties) {
        if (!properties.localLogin().enabled()) {
            return new InMemoryUserDetailsManager();
        }
        if (properties.localLogin().passwordHash() == null || properties.localLogin().passwordHash().isBlank()) {
            throw new IllegalStateException("Local login requires AUTH_LOCAL_LOGIN_PASSWORD_HASH");
        }
        return new InMemoryUserDetailsManager(User
            .withUsername(properties.localLogin().username())
            .password(properties.localLogin().passwordHash())
            .roles("ADMIN")
            .build());
    }

    @Bean
    DaoAuthenticationProvider localPasswordAuthenticationProvider(
        IdentityProperties properties,
        PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(localAdministrator(properties));
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(IdentityProperties properties, ResourceLoader resources) throws Exception {
        String pem;
        try (var input = resources.getResource(properties.signingKeyLocation()).getInputStream()) {
            pem = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load authorization server signing key", exception);
        }
        RSAKey parsed = RSAKey.parseFromPEMEncodedObjects(pem).toRSAKey();
        RSAKey key = new RSAKey.Builder(parsed.toRSAPublicKey())
            .privateKey(parsed.toRSAPrivateKey())
            .keyID("calcifer-auth")
            .build();
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(IdentityProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtClaimsCustomizer(IdentityProperties properties) {
        return context -> {
            boolean clientCredentials
                = AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType());
            context.getClaims().audience(List.of(properties.audienceFor(context.getRegisteredClient().getClientId())));
            if (clientCredentials) {
                context.getClaims().subject(context.getPrincipal().getName());
                context.getClaims().claim("roles", Set.of("service"));
            } else {
                context.getClaims().subject(properties.canonicalUserId());
                context.getClaims().claim("roles", Set.of("admin"));
                context.getClaims().claim("email", properties.allowedGoogleEmail());
            }
            if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
                context.getClaims().claim("email_verified", true);
            }
        };
    }
}
