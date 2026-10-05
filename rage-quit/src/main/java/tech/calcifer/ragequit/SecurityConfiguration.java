package tech.calcifer.ragequit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
public class SecurityConfiguration {
    @Bean
    ClientRegistrationRepository clients(RageQuitProperties properties) {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("rage-quit")
                .clientId(properties.clientId()).clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://rage-quit.calcifer.tech/login/oauth2/code/rage-quit")
                .scope("openid", "profile", "email")
                .issuerUri(properties.issuer())
                .authorizationUri(properties.issuer() + "/oauth2/authorize")
                .tokenUri(properties.issuer() + "/oauth2/token")
                .jwkSetUri(properties.issuer() + "/oauth2/jwks")
                .userInfoUri(properties.issuer() + "/userinfo")
                .userNameAttributeName("sub").clientName("Calcifer").build());
    }

    @Bean
    DefaultOAuth2AuthorizationRequestResolver authorizationRequests(ClientRegistrationRepository clients) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
        // Spring normally enables PKCE only for public clients; explicitly enable it for this confidential client.
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(Clock clock) {
        var factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(client -> idTokenValidator(client, clock));
        // The factory verifies RS256 signatures against the explicit JWK endpoint lazily, during login.
        return factory;
    }

    static OidcIdTokenValidator idTokenValidator(ClientRegistration client, Clock clock) {
        var validator = new OidcIdTokenValidator(client);
        validator.setClock(clock);
        validator.setClockSkew(Duration.ZERO);
        return validator;
    }

    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> admittedUsers() {
        var delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            Participants.requireIdentity(user);
            // No provider roles grant broader application privileges (including for the administrator).
            return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT")),
                    user.getIdToken(), user.getUserInfo(), "sub");
        };
    }

    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http,
            DefaultOAuth2AuthorizationRequestResolver authorizationRequests,
            OAuth2UserService<OidcUserRequest, OidcUser> admittedUsers) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/login.html", "/style.css", "/assets/**", "/css/**", "/js/**",
                                "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED),
                                paths.matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/rage-quit"),
                                paths.matcher("/**")))
                .oauth2Login(login -> login.loginPage("/oauth2/authorization/rage-quit")
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests))
                        .userInfoEndpoint(endpoint -> endpoint.oidcUserService(admittedUsers))
                        .defaultSuccessUrl("/index.html", true)
                        .failureUrl("/login.html?error"))
                .logout(logout -> logout.logoutRequestMatcher(paths.matcher(HttpMethod.POST, "/logout"))
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("RAGE_QUIT_SESSION")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")));
        // Default session-backed CSRF, session fixation protection, state and nonce validation remain enabled.
        return http.build();
    }
}
