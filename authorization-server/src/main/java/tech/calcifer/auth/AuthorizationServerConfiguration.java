package tech.calcifer.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
class AuthorizationServerConfiguration {
  @Bean
  SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
    http.securityMatcher(authorizationServer.getEndpointsMatcher())
        .with(authorizationServer, configurer -> configurer.oidc(Customizer.withDefaults()))
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
        .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
            new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google"),
            new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
    return http.build();
  }

  @Bean
  SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http, IdentityProperties properties, GoogleAdminOidcUserService googleUserService) throws Exception {
    http.authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/internal/traefik/forward-auth").permitAll()
            .anyRequest().authenticated())
        .oauth2Login(login -> login.userInfoEndpoint(endpoint -> endpoint.oidcUserService(googleUserService)));
    if (properties.localLogin().enabled()) {
      http.formLogin(Customizer.withDefaults());
    }
    return http.build();
  }

  @Bean
  RegisteredClientRepository registeredClientRepository(IdentityProperties properties, PasswordEncoder passwordEncoder) {
    RegisteredClient grafana = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId(properties.grafana().id()).clientSecret(passwordEncoder.encode(properties.grafana().secret()))
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri(properties.grafana().redirectUri()).scope("openid").scope("profile").scope("email")
        .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build()).build();
    RegisteredClient api = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId(properties.grafanaApi().id()).clientSecret(passwordEncoder.encode(properties.grafanaApi().secret()))
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("grafana.api").build();
    return new InMemoryRegisteredClientRepository(grafana, api);
  }

  @Bean
  PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

  @Bean
  @ConditionalOnProperty(prefix = "identity.local-login", name = "enabled", havingValue = "true")
  UserDetailsService localAdministrator(IdentityProperties properties) {
    if (properties.localLogin().passwordHash() == null || properties.localLogin().passwordHash().isBlank()) {
      throw new IllegalStateException("Home local login requires AUTH_LOCAL_LOGIN_PASSWORD_HASH");
    }
    return new InMemoryUserDetailsManager(User.withUsername(properties.localLogin().username())
        .password(properties.localLogin().passwordHash()).roles("ADMIN").build());
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
    RSAKey key = new RSAKey.Builder(parsed.toRSAPublicKey()).privateKey(parsed.toRSAPrivateKey()).keyID("calcifer-auth").build();
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
  OAuth2TokenCustomizer<JwtEncodingContext> jwtClaimsCustomizer() {
    return context -> {
      if (context.getTokenType().getValue().equals("access_token")) {
        context.getClaims().audience(List.of("grafana"));
        context.getClaims().claim("roles", Set.of("admin"));
      }
    };
  }
}
