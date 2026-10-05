package tech.calcifer.auth;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;


/**
 * Exercises the production security chain, not the MVC controller that its UserInfo filter bypasses.
 */
class OidcUserInfoEndpointTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void realEndpointReturnsCurrentCatalogGroupsAndLegacyRoles() throws Exception {
        save(
            Set.of("openid", "email", "profile"),
            "user:pugliens",
            true,
            false,
            false,
            IdentityTestAuthentications.google("pugliens@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").value("user:pugliens"))
            .andExpect(jsonPath("$.groups[0]").value("rage-quit"))
            .andExpect(jsonPath("$.roles[0]").value("rage-quit"))
            .andExpect(jsonPath("$.email").value("pugliens@gmail.com"))
            .andExpect(jsonPath("$.name").value("Test participant"))
            .andExpect(jsonPath("$.unrequested").doesNotExist());
    }

    @Test
    void standardClaimsRemainRestrictedToAuthorizedScopes() throws Exception {
        save(
            Set.of("openid"),
            "user:pugliens",
            true,
            false,
            false,
            IdentityTestAuthentications.google("pugliens@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups[0]").value("rage-quit"))
            .andExpect(jsonPath("$.roles[0]").value("rage-quit"))
            .andExpect(jsonPath("$.email").doesNotExist())
            .andExpect(jsonPath("$.email_verified").doesNotExist())
            .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void localAdministratorRetainsItsSubjectRoleAndCatalogEmail() throws Exception {
        save(
            Set.of("openid", "email"),
            "user:admin",
            true,
            false,
            false,
            IdentityTestAuthentications.password("dem.gianluigi@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").value("user:admin"))
            .andExpect(jsonPath("$.groups[0]").value("admin"))
            .andExpect(jsonPath("$.roles[0]").value("admin"))
            .andExpect(jsonPath("$.email").value("dem.gianluigi@gmail.com"));
    }

    @Test
    void rejectsUnknownAndUnstoredTokens() throws Exception {
        mvc.perform(get("/userinfo").header("Authorization", "Bearer unknown")).andExpect(status().isUnauthorized());
        context.getBean(TokenDecoder.class).tokens.put("unstored", jwt("unstored", "user:pugliens"));
        mvc.perform(get("/userinfo").header("Authorization", "Bearer unstored")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredAndInvalidatedTokens() throws Exception {
        save(
            Set.of("openid"),
            "user:pugliens",
            true,
            true,
            false,
            IdentityTestAuthentications.google("pugliens@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isUnauthorized());
        save(
            Set.of("openid"),
            "user:pugliens",
            true,
            false,
            true,
            IdentityTestAuthentications.google("pugliens@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingOpenidScope() throws Exception {
        save(
            Set.of("email"),
            "user:pugliens",
            true,
            false,
            false,
            IdentityTestAuthentications.google("pugliens@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("insufficient_scope"));
    }

    @Test
    void rejectsMissingIdToken() throws Exception {
        // Use a fresh store so the token cannot match the preceding insufficient-scope authorization.
        save(
            Set.of("openid"),
            "user:pugliens",
            false,
            false,
            false,
            IdentityTestAuthentications.google("pugliens@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void rejectsIneligibleAuthenticationMethodAndMismatchedSubject() throws Exception {
        save(
            Set.of("openid"),
            "user:pugliens",
            true,
            false,
            false,
            IdentityTestAuthentications.password("pugliens@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isUnauthorized());
        save(
            Set.of("openid"),
            "user:someone-else",
            true,
            false,
            false,
            IdentityTestAuthentications.google("pugliens@gmail.com")
        );
        mvc
            .perform(get("/userinfo").header("Authorization", "Bearer test-access"))
            .andExpect(status().isUnauthorized());
    }

    private void save(
        Set<String> scopes,
        String subject,
        boolean includeIdToken,
        boolean expired,
        boolean invalidated,
        Authentication principal) {
        Instant now = Instant.now();
        var access = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "test-access",
            now.minusSeconds(expired ? 600 : 0),
            now.plusSeconds(expired ? -300 : 300),
            scopes
        );
        var client = java.util.Objects.requireNonNull(context
            .getBean(RegisteredClientRepository.class)
            .findByClientId(subject.equals("user:admin") ? "grafana" : "rage-quit"));
        var builder = OAuth2Authorization
            .withRegisteredClient(client)
            .principalName(principal.getName())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(scopes)
            .attribute(Principal.class.getName(), principal)
            .token(access, metadata -> metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, invalidated));
        if (includeIdToken) {
            builder.token(new OidcIdToken(
                "test-id", now, now.plusSeconds(300), Map.of(
                "sub",
                subject,
                "email",
                "pugliens@gmail.com",
                "email_verified",
                true,
                "name",
                "Test participant",
                "groups",
                Set.of("admin"),
                "roles",
                Set.of("admin"),
                "unrequested",
                "must-not-leak"
            )
            ));
        }
        context.getBean(OAuth2AuthorizationService.class).save(builder.build());
        context.getBean(TokenDecoder.class).tokens.put("test-access", jwt("test-access", subject));
    }

    private static Jwt jwt(String value, String subject) {
        return Jwt
            .withTokenValue(value)
            .header("alg", "RS256")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    }

    @org.jspecify.annotations.NullMarked
    static class TokenDecoder implements JwtDecoder {

        final Map<String, Jwt> tokens = new HashMap<>();

        @Override
        public Jwt decode(String token) {
            Jwt jwt = tokens.get(token);
            if (jwt == null) {
                throw new BadJwtException("Unknown test token");
            }
            return jwt;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfiguration {

        @Bean
        IdentityProperties identityProperties() {
            return InteractiveClientGroupPolicyTest.properties();
        }

        @Bean
        TokenDecoder jwtDecoder() {
            return new TokenDecoder();
        }

        @Bean
        OAuth2AuthorizationService authorizationService() {
            return new InMemoryOAuth2AuthorizationService();
        }

        @Bean
        AuthorizationServerSettings authorizationServerSettings() {
            return AuthorizationServerSettings.builder().issuer("https://auth.calcifer.tech").build();
        }

        @Bean
        RegisteredClientRepository clients() {
            return new InMemoryRegisteredClientRepository(java.util.List.of(
                registeredClient("grafana"),
                registeredClient("rage-quit")
            ));
        }

        private static RegisteredClient registeredClient(String clientId) {
            return RegisteredClient
                .withId("registered-" + clientId)
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.test/callback")
                .scope("openid")
                .scope("email")
                .scope("profile")
                .build();
        }

        @Bean
        SecurityFilterChain authorizationChain(HttpSecurity http, IdentityProperties properties) throws Exception {
            return new AuthorizationServerConfiguration().authorizationServerSecurityFilterChain(http, properties,
                authorizationService(), clients());
        }
    }
}