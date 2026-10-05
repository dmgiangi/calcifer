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
class RageQuitOidcFlowTest {

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
    @ValueSource(strings = {"dem.gianluigi@gmail.com", "pugliens@gmail.com", "frevadiscor@gmail.com"})
    void authorizesExchangesAndReturnsRealUserInfoForEveryParticipant(String email) throws Exception {
        var principal = google(email);
        String code = code(principal);
        var response = exchange(code, VERIFIER, RageQuitTestSupport.CALLBACK, "rage-quit", "test-rage-secret");
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        var tokens = JsonMapper.builder().build().readTree(response.getResponse().getContentAsString());
        for (String token : Set.of("access_token", "id_token")) {
            var jwt = context.getBean(JwtDecoder.class).decode(tokens.get(token).asText());
            assertThat(jwt.getSubject()).isEqualTo(RageQuitTestSupport.SUBJECTS.get(email));
            assertThat(jwt.getAudience()).containsExactly("rage-quit");
            assertThat(jwt.getClaimAsString("email")).isEqualTo(email);
            assertThat(jwt.getClaimAsBoolean("email_verified")).isTrue();
            assertThat(jwt.getClaimAsStringList("roles"))
                .containsExactly(email.equals(RageQuitTestSupport.ADMIN) ? "admin" : "rage-quit-user");
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
        assertThat(claims.get("sub").asText()).isEqualTo(RageQuitTestSupport.SUBJECTS.get(email));
        assertThat(claims.get("email").asText()).isEqualTo(email);
        assertThat(claims.get("email_verified").asBoolean()).isTrue();
        assertThat(claims.get("roles").get(0).asText())
            .isEqualTo(email.equals(RageQuitTestSupport.ADMIN) ? "admin" : "rage-quit-user");
        assertThat(exchange(code, VERIFIER, RageQuitTestSupport.CALLBACK, "rage-quit", "test-rage-secret")
            .getResponse().getStatus()).isBetween(400, 499);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pugliens@gmail.com", "frevadiscor@gmail.com"})
    void reusedSessionsAreDeniedBeforeCodesForOtherClients(String email) throws Exception {
        var clients = context.getBean(RegisteredClientRepository.class);
        for (String id : Set.of("grafana", "homepage", "home-assistant", "zigbee2mqtt", "grafana-api", "unknown")) {
            var client = clients.findByClientId(id);
            String callback = client != null && !client.getRedirectUris().isEmpty()
                ? client.getRedirectUris().iterator().next() : RageQuitTestSupport.CALLBACK;
            assertNoCode(authorize(RageQuitTestSupport.google(email), id, callback, "openid", challenge()));
        }
    }

    @Test
    void passwordSessionStartsGoogleAndPreservesTheEntireSavedRequest() throws Exception {
        var result = authorize(RageQuitTestSupport.password(), "rage-quit", RageQuitTestSupport.CALLBACK,
            "openid profile email", challenge());
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/oauth2/authorization/google");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callback.setSession(session);
        var response = new MockHttpServletResponse();
        var cache = context.getBean(RequestCache.class);
        String original = cache.getRequest(callback, response).getRedirectUrl();
        assertThat(original).contains("client_id=rage-quit", "state=test-state", "nonce=test-nonce",
            "code_challenge=", "code_challenge_method=S256", "redirect_uri=");
        var principal = google(RageQuitTestSupport.ADMIN);
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
        var exchanged = exchange(code, VERIFIER, RageQuitTestSupport.CALLBACK, "rage-quit", "test-rage-secret");
        assertThat(exchanged.getResponse().getStatus()).isEqualTo(200);
        var tokens = JsonMapper.builder().build().readTree(exchanged.getResponse().getContentAsString());
        var idToken = context.getBean(JwtDecoder.class).decode(tokens.get("id_token").asText());
        assertThat(idToken.getClaimAsString("nonce")).isEqualTo("test-nonce");
        assertThat(idToken.getClaimAsInstant("auth_time")).isNotNull();
    }

    @Test
    void rejectsCallbacksScopesAndMissingOrUnsupportedPkceBeforeCodeIssuance() throws Exception {
        var principal = RageQuitTestSupport.google(RageQuitTestSupport.ADMIN);
        for (String callback : Set.of("https://evil.example.test/callback", "https://rage-quit.calcifer.tech/login/oauth2/code/*",
            RageQuitTestSupport.CALLBACK + "/extra")) {
            assertNoCode(authorize(principal, "rage-quit", callback, "openid", challenge()));
        }
        assertNoCode(authorize(principal, "rage-quit", RageQuitTestSupport.CALLBACK, "openid grafana.api", challenge()));
        assertNoCode(authorize(principal, "rage-quit", RageQuitTestSupport.CALLBACK, "openid offline_access", challenge()));
        assertNoCode(authorize(principal, "rage-quit", RageQuitTestSupport.CALLBACK, "openid", null));
        assertNoCode(mvc.perform(get("/oauth2/authorize").with(authentication(principal))
            .param("response_type", "code").param("client_id", "rage-quit").param("redirect_uri", RageQuitTestSupport.CALLBACK)
            .param("scope", "openid").param("code_challenge", VERIFIER).param("code_challenge_method", "plain"))
            .andReturn());
    }

    @Test
    void rejectsIncorrectOrMissingVerifierClientAuthenticationAndCrossClientExchange() throws Exception {
        for (String verifier : Set.of("b".repeat(43), "")) {
            assertThat(exchange(code(RageQuitTestSupport.ADMIN), verifier, RageQuitTestSupport.CALLBACK,
                "rage-quit", "test-rage-secret").getResponse().getStatus()).isBetween(400, 499);
        }
        assertThat(exchange(code(RageQuitTestSupport.ADMIN), VERIFIER, "https://evil.example.test/callback",
            "rage-quit", "test-rage-secret").getResponse().getStatus()).isBetween(400, 499);
        assertThat(exchange(code(RageQuitTestSupport.ADMIN), VERIFIER, RageQuitTestSupport.CALLBACK,
            "rage-quit", "wrong-test-secret").getResponse().getStatus()).isBetween(400, 499);
        assertThat(exchange(code(RageQuitTestSupport.ADMIN), VERIFIER, RageQuitTestSupport.CALLBACK,
            "homepage", "test-other-secret").getResponse().getStatus()).isBetween(400, 499);
        mvc.perform(post("/oauth2/token").param("grant_type", "client_credentials")
            .with(httpBasic("rage-quit", "test-rage-secret"))).andExpect(status().is4xxClientError());
        mvc.perform(post("/oauth2/token").param("grant_type", "refresh_token").param("refresh_token", "test-refresh")
            .with(httpBasic("rage-quit", "test-rage-secret"))).andExpect(status().is4xxClientError());
        mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code").param("code", code(RageQuitTestSupport.ADMIN))
            .param("redirect_uri", RageQuitTestSupport.CALLBACK).param("code_verifier", VERIFIER)
            .param("client_id", "rage-quit").param("client_secret", "test-rage-secret"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void persistedPasswordOrLegacyPrincipalCannotBeExchangedEvenWithValidPkce() throws Exception {
        for (Authentication principal : java.util.List.of(RageQuitTestSupport.password(),
            new org.springframework.security.authentication.TestingAuthenticationToken(RageQuitTestSupport.ADMIN,
                "ignored", "ROLE_ADMIN"))) {
            String code = code(RageQuitTestSupport.ADMIN);
            var service = context.getBean(OAuth2AuthorizationService.class);
            var stored = service.findByToken(code, new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("code"));
            service.save(OAuth2Authorization.from(stored).attribute(Principal.class.getName(), principal).build());
            assertThat(exchange(code, VERIFIER, RageQuitTestSupport.CALLBACK, "rage-quit", "test-rage-secret")
                .getResponse().getStatus()).isBetween(400, 499);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pugliens@gmail.com", "frevadiscor@gmail.com"})
    void persistedAuthorizationForAnotherClientIsDefensivelyDeniedAtExchange(String email) throws Exception {
        for (String client : Set.of("grafana", "homepage", "home-assistant", "zigbee2mqtt")) {
            var registration = context.getBean(RegisteredClientRepository.class).findByClientId(client);
            String callback = registration.getRedirectUris().iterator().next();
            var authorized = authorize(RageQuitTestSupport.password(), client, callback, "openid", challenge());
            String code = query(authorized, "code");
            assertThat(code).isNotBlank();
            var service = context.getBean(OAuth2AuthorizationService.class);
            var stored = service.findByToken(code, new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("code"));
            service.save(OAuth2Authorization.from(stored)
                .attribute(Principal.class.getName(), RageQuitTestSupport.google(email)).build());
            assertThat(exchange(code, VERIFIER, callback, client, client.equals("grafana") ? "secret" : "test-other-secret")
                .getResponse().getStatus()).isBetween(400, 499);
        }
    }

    @Test
    void oldSessionReauthenticatesAndSilentPasswordSessionNeverIssuesCode() throws Exception {
        var legacy = new org.springframework.security.authentication.TestingAuthenticationToken(
            RageQuitTestSupport.ADMIN, "ignored", "ROLE_ADMIN");
        assertThat(authorize(legacy, "rage-quit", RageQuitTestSupport.CALLBACK, "openid", challenge())
            .getResponse().getRedirectedUrl()).isEqualTo("/oauth2/authorization/google");
        mvc.perform(get("/oauth2/authorize").with(authentication(RageQuitTestSupport.password()))
            .param("client_id", "rage-quit").param("response_type", "code").param("scope", "openid")
            .param("redirect_uri", RageQuitTestSupport.CALLBACK).param("prompt", "none")
            .param("code_challenge", challenge()).param("code_challenge_method", "S256"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void administratorFallbackAndMachineContractStayUnchanged() throws Exception {
        var authorized = authorize(RageQuitTestSupport.password(), "grafana",
            "https://grafana.calcifer.tech/login/generic_oauth", "openid profile email", challenge());
        assertThat(query(authorized, "code")).isNotBlank();
        var exchanged = exchange(query(authorized, "code"), VERIFIER,
            "https://grafana.calcifer.tech/login/generic_oauth", "grafana", "secret");
        assertThat(exchanged.getResponse().getStatus()).isEqualTo(200);
        var interactiveTokens = JsonMapper.builder().build().readTree(exchanged.getResponse().getContentAsString());
        var idToken = context.getBean(JwtDecoder.class).decode(interactiveTokens.get("id_token").asText());
        assertThat(idToken.getSubject()).isEqualTo("user:admin");
        assertThat(idToken.getClaimAsStringList("roles")).containsExactly("admin");
        assertThat(idToken.getClaimAsInstant("auth_time")).isNotNull();
        var result = mvc.perform(post("/oauth2/token").with(httpBasic("grafana-api", "secret"))
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
        var source = (OidcUser) RageQuitTestSupport.google(email).getPrincipal();
        var principal = new GoogleAdminOidcUserService(request -> source, context.getBean(IdentityProperties.class))
            .loadUser(null);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    private String code(Authentication principal) throws Exception {
        var result = authorize(principal, "rage-quit", RageQuitTestSupport.CALLBACK,
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
        @Bean IdentityProperties properties() { return RageQuitTestSupport.properties(); }
        @Bean OAuth2AuthorizationService authorizations() { return new InMemoryOAuth2AuthorizationService(); }
        @Bean AuthorizationServerSettings settings() {
            return AuthorizationServerSettings.builder().issuer("https://auth.calcifer.tech").build();
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
