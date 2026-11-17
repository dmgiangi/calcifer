package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tech.calcifer.auth.state.AuthorizationStateProperties;
import tools.jackson.databind.json.JsonMapper;

/** Uses the production providers, JWT generator and HTTP filters with synthetic identities only. */
class InteractiveClientOidcFlowTest {

    private static final String VERIFIER = "a".repeat(43);
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
    void close() {
        context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin@example.test", "alice@example.test", "bob@example.test"})
    void authorizesExchangesAndReturnsRealUserInfoForEveryParticipant(String email) throws Exception {
        var principal = google(email);
        String code = code(principal);
        var response = exchange(code, VERIFIER, InteractiveClientTestSupport.CALLBACK, "restricted-client", "test-client-secret");
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        var tokens = JsonMapper.builder().build().readTree(response.getResponse().getContentAsString());
        for (String token : Set.of("access_token", "id_token")) {
            var jwt = context.getBean(JwtDecoder.class).decode(tokens.get(token).asText());
            assertThat(jwt.getSubject()).isEqualTo(InteractiveClientTestSupport.SUBJECTS.get(email));
            assertThat(jwt.getAudience()).containsExactly("restricted-client");
            assertThat(jwt.getClaimAsString("email")).isEqualTo(email);
            assertThat(jwt.getClaimAsBoolean("email_verified")).isTrue();
            assertThat(jwt.getClaimAsStringList("roles"))
                .containsExactly(email.equals(InteractiveClientTestSupport.ADMIN) ? "admin" : "participant");
            if (token.equals("id_token")) {
                var factor = principal.getAuthorities().stream().filter(FactorGrantedAuthority.class::isInstance)
                    .map(FactorGrantedAuthority.class::cast).findFirst().orElseThrow();
                assertThat(jwt.getClaimAsInstant("auth_time")).isEqualTo(factor.getIssuedAt().truncatedTo(ChronoUnit.SECONDS));
                assertThat(jwt.getClaimAsString("sid")).isNotBlank();
                assertThat(jwt.getClaimAsString("nonce")).isEqualTo("test-nonce");
            }
        }
        assertThat(tokens.has("refresh_token")).isFalse();
        assertThat(Set.of(tokens.get("scope").asText().split(" "))).containsExactlyInAnyOrder("openid", "profile", "email");
        var userInfo = mvc.perform(get("/userinfo").header("Authorization", "Bearer " + tokens.get("access_token").asText()))
            .andExpect(status().isOk()).andReturn();
        var claims = JsonMapper.builder().build().readTree(userInfo.getResponse().getContentAsString());
        assertThat(claims.get("sub").asText()).isEqualTo(InteractiveClientTestSupport.SUBJECTS.get(email));
        assertThat(claims.get("email").asText()).isEqualTo(email);
        assertThat(claims.get("email_verified").asBoolean()).isTrue();
        assertThat(claims.get("roles").get(0).asText())
            .isEqualTo(email.equals(InteractiveClientTestSupport.ADMIN) ? "admin" : "participant");
        assertThat(exchange(code, VERIFIER, InteractiveClientTestSupport.CALLBACK, "restricted-client", "test-client-secret")
            .getResponse().getStatus()).isBetween(400, 499);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.test", "bob@example.test"})
    void reusedSessionsAreDeniedBeforeCodesForOtherClients(String email) throws Exception {
        var clients = context.getBean(RegisteredClientRepository.class);
        for (String id : Set.of("grafana", "admin-console", "admin-reports", "admin-settings", "grafana-api", "unknown")) {
            var client = clients.findByClientId(id);
            String callback = client != null && !client.getRedirectUris().isEmpty()
                ? client.getRedirectUris().iterator().next() : InteractiveClientTestSupport.CALLBACK;
            assertNoCode(authorize(InteractiveClientTestSupport.google(email), id, callback, "openid", challenge()));
        }
    }

    @Test
    void anonymousUserSeesCentralLoginAndKeepsTheEntireAuthorizationRequest() throws Exception {
        var result = mvc.perform(get("/oauth2/authorize").queryParam("response_type", "code")
            .queryParam("client_id", "restricted-client").queryParam("redirect_uri", InteractiveClientTestSupport.CALLBACK)
            .queryParam("scope", "openid profile email").queryParam("state", "test-state")
            .queryParam("nonce", "test-nonce").queryParam("code_challenge", challenge())
            .queryParam("code_challenge_method", "S256")).andExpect(status().isFound()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callback.setSession(session);
        var response = new MockHttpServletResponse();
        var cache = context.getBean(RequestCache.class);
        String original = cache.getRequest(callback, response).getRedirectUrl();
        assertThat(original).contains("client_id=restricted-client", "state=test-state", "nonce=test-nonce",
            "code_challenge=", "code_challenge_method=S256", "redirect_uri=");
        var principal = google(InteractiveClientTestSupport.ADMIN);
        new AuthorizationServerConfiguration().oauth2LoginSuccessHandler(cache)
            .onAuthenticationSuccess(callback, response, principal);
        assertThat(response.getRedirectedUrl()).isEqualTo(original);
        var resumed = mvc.perform(get(URI.create(original)).session(session).with(authentication(principal))).andReturn();
        assertThat(query(resumed, "code")).isNotBlank();
        assertThat(query(resumed, "state")).isEqualTo("test-state");
        assertThat(cache.getRequest(callback, response)).isNull();
    }

    @Test
    void silentAnonymousAuthorizationDoesNotShowLoginOrIssueCode() throws Exception {
        var result = mvc.perform(get("/oauth2/authorize").param("response_type", "code")
            .param("client_id", "restricted-client").param("redirect_uri", InteractiveClientTestSupport.CALLBACK)
            .param("scope", "openid").param("prompt", "none")
            .param("code_challenge", challenge()).param("code_challenge_method", "S256"))
            .andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void passwordSessionStartsGoogleAndPreservesTheEntireSavedRequest() throws Exception {
        var result = authorize(InteractiveClientTestSupport.password(), "restricted-client", InteractiveClientTestSupport.CALLBACK,
            "openid profile email", challenge());
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/oauth2/authorization/google");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callback.setSession(session);
        var response = new MockHttpServletResponse();
        var cache = context.getBean(RequestCache.class);
        String original = cache.getRequest(callback, response).getRedirectUrl();
        assertThat(original).contains("client_id=restricted-client", "state=test-state", "nonce=test-nonce",
            "code_challenge=", "code_challenge_method=S256", "redirect_uri=");
        var principal = google(InteractiveClientTestSupport.ADMIN);
        new AuthorizationServerConfiguration().oauth2LoginSuccessHandler(cache)
            .onAuthenticationSuccess(callback, response, principal);
        assertThat(response.getRedirectedUrl()).isEqualTo(original);
        // Replay the already-encoded saved URL unchanged in the same session.
        var resumed = mvc.perform(get(URI.create(original)).session(session).with(authentication(principal))).andReturn();
        assertThat(query(resumed, "state")).isEqualTo("test-state");
        assertThat(query(resumed, "error")).isNull();
        String code = query(resumed, "code");
        assertThat(code).isNotBlank();
        assertThat(cache.getRequest(callback, response)).isNull();
        var exchanged = exchange(code, VERIFIER, InteractiveClientTestSupport.CALLBACK, "restricted-client", "test-client-secret");
        assertThat(exchanged.getResponse().getStatus()).isEqualTo(200);
        var tokens = JsonMapper.builder().build().readTree(exchanged.getResponse().getContentAsString());
        var idToken = context.getBean(JwtDecoder.class).decode(tokens.get("id_token").asText());
        assertThat(idToken.getClaimAsString("nonce")).isEqualTo("test-nonce");
        assertThat(idToken.getClaimAsInstant("auth_time")).isNotNull();
    }

    @Test
    void rejectsCallbacksScopesAndMissingOrUnsupportedPkceBeforeCodeIssuance() throws Exception {
        var principal = InteractiveClientTestSupport.google(InteractiveClientTestSupport.ADMIN);
        for (String callback : Set.of("https://evil.example.test/callback", "https://client.example.test/login/oauth2/code/*",
            InteractiveClientTestSupport.CALLBACK + "/extra")) {
            assertNoCode(authorize(principal, "restricted-client", callback, "openid", challenge()));
        }
        assertNoCode(authorize(principal, "restricted-client", InteractiveClientTestSupport.CALLBACK, "openid grafana.api", challenge()));
        assertNoCode(authorize(principal, "restricted-client", InteractiveClientTestSupport.CALLBACK, "openid offline_access", challenge()));
        assertNoCode(authorize(principal, "restricted-client", InteractiveClientTestSupport.CALLBACK, "openid", null));
        assertNoCode(mvc.perform(get("/oauth2/authorize").with(authentication(principal))
            .param("response_type", "code").param("client_id", "restricted-client").param("redirect_uri", InteractiveClientTestSupport.CALLBACK)
            .param("scope", "openid").param("code_challenge", VERIFIER).param("code_challenge_method", "plain"))
            .andReturn());
    }

    @Test
    void rejectsIncorrectOrMissingVerifierClientAuthenticationAndCrossClientExchange() throws Exception {
        for (String verifier : Set.of("b".repeat(43), "")) {
            assertThat(exchange(code(InteractiveClientTestSupport.ADMIN), verifier, InteractiveClientTestSupport.CALLBACK,
                "restricted-client", "test-client-secret").getResponse().getStatus()).isBetween(400, 499);
        }
        assertThat(exchange(code(InteractiveClientTestSupport.ADMIN), VERIFIER, "https://evil.example.test/callback",
            "restricted-client", "test-client-secret").getResponse().getStatus()).isBetween(400, 499);
        assertThat(exchange(code(InteractiveClientTestSupport.ADMIN), VERIFIER, InteractiveClientTestSupport.CALLBACK,
            "restricted-client", "wrong-test-secret").getResponse().getStatus()).isBetween(400, 499);
        assertThat(exchange(code(InteractiveClientTestSupport.ADMIN), VERIFIER, InteractiveClientTestSupport.CALLBACK,
            "admin-console", "test-other-secret").getResponse().getStatus()).isBetween(400, 499);
        mvc.perform(post("/oauth2/token").param("grant_type", "client_credentials")
            .with(httpBasic("restricted-client", "test-client-secret"))).andExpect(status().is4xxClientError());
        mvc.perform(post("/oauth2/token").param("grant_type", "refresh_token").param("refresh_token", "test-refresh")
            .with(httpBasic("restricted-client", "test-client-secret"))).andExpect(status().is4xxClientError());
        mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code").param("code", code(InteractiveClientTestSupport.ADMIN))
            .param("redirect_uri", InteractiveClientTestSupport.CALLBACK).param("code_verifier", VERIFIER)
            .param("client_id", "restricted-client").param("client_secret", "test-client-secret"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void persistedPasswordOrLegacyPrincipalCannotBeExchangedEvenWithValidPkce() throws Exception {
        for (Authentication principal : java.util.List.of(InteractiveClientTestSupport.password(),
            new org.springframework.security.authentication.TestingAuthenticationToken(InteractiveClientTestSupport.ADMIN,
                "ignored", "ROLE_ADMIN"))) {
            String code = code(InteractiveClientTestSupport.ADMIN);
            var service = context.getBean(OAuth2AuthorizationService.class);
            var stored = service.findByToken(code, new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("code"));
            service.save(OAuth2Authorization.from(stored).attribute(Principal.class.getName(), principal).build());
            assertThat(exchange(code, VERIFIER, InteractiveClientTestSupport.CALLBACK, "restricted-client", "test-client-secret")
                .getResponse().getStatus()).isBetween(400, 499);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.test", "bob@example.test"})
    void persistedAuthorizationForAnotherClientIsDefensivelyDeniedAtExchange(String email) throws Exception {
        for (String client : Set.of("grafana", "admin-console", "admin-reports", "admin-settings")) {
            var registration = context.getBean(RegisteredClientRepository.class).findByClientId(client);
            String callback = registration.getRedirectUris().iterator().next();
            var authorized = authorize(InteractiveClientTestSupport.password(), client, callback, "openid", challenge());
            String code = query(authorized, "code");
            assertThat(code).isNotBlank();
            var service = context.getBean(OAuth2AuthorizationService.class);
            var stored = service.findByToken(code, new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("code"));
            service.save(OAuth2Authorization.from(stored)
                .attribute(Principal.class.getName(), InteractiveClientTestSupport.google(email)).build());
            assertThat(exchange(code, VERIFIER, callback, client, client.equals("grafana") ? "test-legacy-secret" : "test-other-secret")
                .getResponse().getStatus()).isBetween(400, 499);
        }
    }

    @Test
    void oldSessionReauthenticatesAndSilentPasswordSessionNeverIssuesCode() throws Exception {
        var legacy = new org.springframework.security.authentication.TestingAuthenticationToken(
            InteractiveClientTestSupport.ADMIN, "ignored", "ROLE_ADMIN");
        assertThat(authorize(legacy, "restricted-client", InteractiveClientTestSupport.CALLBACK, "openid", challenge())
            .getResponse().getRedirectedUrl()).isEqualTo("/oauth2/authorization/google");
        mvc.perform(get("/oauth2/authorize").with(authentication(InteractiveClientTestSupport.password()))
            .param("client_id", "restricted-client").param("response_type", "code").param("scope", "openid")
            .param("redirect_uri", InteractiveClientTestSupport.CALLBACK).param("prompt", "none")
            .param("code_challenge", challenge()).param("code_challenge_method", "S256"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void administratorFallbackAndMachineContractStayUnchanged() throws Exception {
        var authorized = authorize(InteractiveClientTestSupport.password(), "grafana",
            "https://grafana.example.test/login/generic_oauth", "openid profile email", challenge());
        assertThat(query(authorized, "code")).isNotBlank();
        var exchanged = exchange(query(authorized, "code"), VERIFIER,
            "https://grafana.example.test/login/generic_oauth", "grafana", "test-legacy-secret");
        assertThat(exchanged.getResponse().getStatus()).isEqualTo(200);
        var interactiveTokens = JsonMapper.builder().build().readTree(exchanged.getResponse().getContentAsString());
        var idToken = context.getBean(JwtDecoder.class).decode(interactiveTokens.get("id_token").asText());
        assertThat(idToken.getSubject()).isEqualTo("user:admin");
        assertThat(idToken.getClaimAsStringList("roles")).containsExactly("admin");
        assertThat(idToken.getClaimAsInstant("auth_time")).isNotNull();
        var result = mvc.perform(post("/oauth2/token").with(httpBasic("grafana-api", "test-legacy-secret"))
            .param("grant_type", "client_credentials").param("scope", "grafana.api"))
            .andExpect(status().isOk()).andReturn();
        var tokens = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        var jwt = context.getBean(JwtDecoder.class).decode(tokens.get("access_token").asText());
        assertThat(jwt.getSubject()).isEqualTo("grafana-api");
        assertThat(jwt.getAudience()).containsExactly("grafana");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("service");
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "email_verified", "groups");
    }

    private String code(String email) throws Exception {
        return code(google(email));
    }

    private OAuth2AuthenticationToken google(String email) {
        var source = (OidcUser) InteractiveClientTestSupport.google(email).getPrincipal();
        var principal = new GoogleAdminOidcUserService(request -> source, context.getBean(IdentityProperties.class))
            .loadUser(null);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    private String code(Authentication principal) throws Exception {
        var result = authorize(principal, "restricted-client", InteractiveClientTestSupport.CALLBACK,
            "openid profile email", challenge());
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(query(result, "state")).isEqualTo("test-state");
        String code = query(result, "code");
        assertThat(code).isNotBlank();
        return code;
    }

    private MvcResult authorize(Authentication principal, String client, String callback, String scopes, String challenge)
        throws Exception {
        var request = get("/oauth2/authorize").with(authentication(principal)).queryParam("response_type", "code")
            .queryParam("client_id", client).queryParam("redirect_uri", callback).queryParam("scope", scopes)
            .queryParam("state", "test-state").queryParam("nonce", "test-nonce");
        if (challenge != null) {
            request.queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256");
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult exchange(String code, String verifier, String callback, String client, String secret) throws Exception {
        var request = post("/oauth2/token").with(httpBasic(client, secret)).param("grant_type", "authorization_code")
            .param("code", code).param("redirect_uri", callback);
        if (!verifier.isEmpty()) {
            request.param("code_verifier", verifier);
        }
        return mvc.perform(request).andReturn();
    }

    private static String challenge() throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String query(MvcResult result, String key) {
        String location = result.getResponse().getRedirectedUrl();
        return location == null ? null : UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst(key);
    }

    private static void assertNoCode(MvcResult result) {
        assertThat(query(result, "code")).isNull();
        assertThat(result.getResponse().getStatus()).isBetween(300, 499);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfiguration {
        @Bean IdentityProperties properties() { return InteractiveClientTestSupport.properties(); }
        @Bean OAuth2AuthorizationService authorizations() { return new InMemoryOAuth2AuthorizationService(); }
        @Bean AuthorizationServerSettings settings() {
            return AuthorizationServerSettings.builder().issuer("https://auth.example.test").build();
        }
        @Bean JWKSource<SecurityContext> keys() throws Exception {
            RSAKey key = new RSAKeyGenerator(2048).keyID("synthetic-test-key").generate();
            return new ImmutableJWKSet<>(new JWKSet(key));
        }
        @Bean JwtDecoder decoder(JWKSource<SecurityContext> keys) {
            return OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);
        }
        @Bean OAuth2TokenCustomizer<JwtEncodingContext> customizer(IdentityProperties properties) {
            return new AuthorizationServerConfiguration().jwtClaimsCustomizer(properties);
        }
        @Bean RegisteredClientRepository clients(IdentityProperties properties) {
            var state = new AuthorizationStateProperties(false, AuthorizationStateProperties.Role.CLOUD,
                new AuthorizationStateProperties.Redis("localhost", 6379, "", "", "auth"), 3,
                Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofSeconds(20), Duration.ofSeconds(3), 100, Duration.ofMinutes(5));
            return new AuthorizationServerConfiguration().registeredClientRepository(properties,
                PasswordEncoderFactories.createDelegatingPasswordEncoder(), state);
        }
        @Bean RequestCache cache() { return new AuthorizationServerConfiguration().authorizationRequestCache(); }
        @Bean SecurityFilterChain chain(HttpSecurity http, IdentityProperties properties, OAuth2AuthorizationService service,
            RegisteredClientRepository clients) throws Exception {
            return new AuthorizationServerConfiguration().authorizationServerSecurityFilterChain(http, properties, service, clients);
        }
    }
}