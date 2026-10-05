package tech.calcifer.ragequit;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityApiTest.ProviderConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityApiTest {
    private static final FakeIssuer ISSUER = new FakeIssuer();
    private static final java.nio.file.Path DIRECTORY = temporaryDirectory();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        properties.add("rage-quit.issuer", () -> "https://auth.calcifer.tech");
        properties.add("rage-quit.client-id", () -> "rage-quit");
        properties.add("rage-quit.client-secret", () -> "synthetic-test-fixture");
        properties.add("rage-quit.database", () -> DIRECTORY.resolve("application.sqlite").toString());
        properties.add("rage-quit.start-date", () -> "2026-01-01");
    }

    @AfterAll static void stopProvider() throws IOException {
        ISSUER.server.stop(0);
        // Remove only this test's freshly allocated disposable directory, never operator storage.
        try (var files = Files.walk(DIRECTORY)) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    @Test @Order(1)
    void startupDoesNotContactProviderAndAnonymousDataIsDenied() throws Exception {
        assertThat(ISSUER.requests.get()).isZero();
        assertThat(request("GET", "/api/session", null, null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/events", null, null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/insights", null, null, null).statusCode()).isEqualTo(401);
        for (String path : List.of("/", "/index.html")) {
            var page = request("GET", path, null, null, null);
            assertThat(page.statusCode()).isEqualTo(302);
            String authorizationPath = URI.create(page.headers().firstValue("Location").orElseThrow()).getPath();
            assertThat(authorizationPath).isEqualTo("/oauth2/authorization/rage-quit");
            var authorization = request("GET", authorizationPath, cookie(page), null, null);
            assertThat(authorization.statusCode()).isEqualTo(302);
            var parameters = parameters(URI.create(authorization.headers().firstValue("Location").orElseThrow()).getRawQuery());
            assertThat(parameters.get("client_id")).isEqualTo("rage-quit");
            assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
            assertThat(parameters.get("state")).isNotBlank();
            assertThat(parameters.get("nonce")).isNotBlank();
        }
        assertThat(request("GET", "/actuator/env", null, null, null).statusCode()).isNotEqualTo(200);
        assertThat(request("GET", "/actuator/health/readiness", null, null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/health/liveness", null, null, null).statusCode()).isEqualTo(200);
        assertThat(ISSUER.requests.get()).isZero();
    }

    static Stream<Participants.Participant> participants() { return Participants.ALL.stream(); }

    @ParameterizedTest @MethodSource("participants")
    void allThreeCanonicalUsersCompleteSignedOidcPkceLogin(Participants.Participant participant) throws Exception {
        Login login = login(participant, Invalid.NONE);
        var session = request("GET", "/api/session", login.cookie(), null, null);
        assertThat(session.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(session.body());
        assertThat(body.get("alias").asText()).isEqualTo(participant.alias());
        assertThat(body.has("csrfToken")).isTrue();
        assertThat(body.get("csrfHeader").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(body.has("email")).isFalse();
        assertThat(body.has("subject")).isFalse();
        assertThat(body.has("access_token")).isFalse();
        assertThat(body.has("id_token")).isFalse();
        assertThat(session.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(ISSUER.pkceValid).isTrue();
        assertThat(ISSUER.basicAuthentication).isTrue();
    }

    enum Invalid { NONE, WRONG_ISSUER, WRONG_AUDIENCE, EXPIRED, MISSING_EXPIRY, BAD_SIGNATURE, MALFORMED,
        UNVERIFIED, MISSING_VERIFIED, UNKNOWN_SUBJECT, MISSING_SUBJECT, SWAPPED_EMAIL, WRONG_NONCE, USERINFO_SUBJECT,
        MULTI_AUDIENCE_MISSING_AZP, WRONG_AZP }

    @ParameterizedTest @EnumSource(value = Invalid.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void invalidSignedIdentitiesNeverEstablishASession(Invalid invalid) throws Exception {
        Login denied = login(Participants.ALL.getFirst(), invalid);
        assertThat(request("GET", "/api/events", denied.cookie(), null, null).statusCode()).isEqualTo(401);
    }

    @Test void stateMismatchMissingStateAndReplayedCallbackFail() throws Exception {
        var begin = begin(Participants.ALL.getFirst(), Invalid.NONE);
        int before = ISSUER.requests.get();
        var mismatch = request("GET", "/login/oauth2/code/rage-quit?code=synthetic&state=wrong", begin.cookie(), null, null);
        assertThat(mismatch.statusCode()).isEqualTo(302);
        assertThat(ISSUER.requests.get()).isEqualTo(before);
        assertThat(request("GET", "/api/session", begin.cookie(), null, null).statusCode()).isEqualTo(401);
        var missing = request("GET", "/login/oauth2/code/rage-quit?code=synthetic", null, null, null);
        assertThat(missing.statusCode()).isEqualTo(302);
        var valid = login(Participants.ALL.getFirst(), Invalid.NONE);
        int after = ISSUER.requests.get();
        request("GET", "/login/oauth2/code/rage-quit?code=synthetic&state=" + encode(valid.state()), valid.cookie(), null, null);
        assertThat(ISSUER.requests.get()).isEqualTo(after);
    }

    @Test void secureCookieCsrfPostLogoutAndSessionFixationProtection() throws Exception {
        Login login = login(Participants.ALL.getFirst(), Invalid.NONE);
        assertThat(login.cookieChanged()).isTrue();
        assertThat(login.secureCookie()).isTrue();
        assertThat(login.httpOnlyCookie()).isTrue();
        assertThat(login.laxCookie()).isTrue();
        var session = json.readTree(request("GET", "/api/session", login.cookie(), null, null).body());
        var input = Map.of("type", "SMOKED", "idempotencyKey", UUID.randomUUID().toString(), "time", Map.of("now", true));
        assertThat(request("POST", "/api/events", login.cookie(), input, null).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/events", login.cookie(), input, "invalid").statusCode()).isEqualTo(403);
        assertThat(request("POST", "/logout", login.cookie(), null, null).statusCode()).isEqualTo(403);
        request("GET", "/logout", login.cookie(), null, null);
        assertThat(request("GET", "/api/session", login.cookie(), null, null).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/events", login.cookie(), input, session.get("csrfToken").asText()).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/logout", login.cookie(), null, session.get("csrfToken").asText()).statusCode()).isEqualTo(204);
        assertThat(request("GET", "/api/session", login.cookie(), null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/events", login.cookie(), null, null).statusCode()).isEqualTo(401);
        // The pre-login session was also invalidated by Spring's session-fixation protection.
        assertThat(request("GET", "/api/session", login.originalCookie(), null, null).statusCode()).isEqualTo(401);
    }

    @Test void apiContractRejectsForgedOwnershipAndProtectsCrossUserVersions() throws Exception {
        Login participant = login(Participants.ALL.get(1), Invalid.NONE);
        String csrf = csrf(participant);
        String key = UUID.randomUUID().toString();
        var input = Map.of("type", "RESISTED", "idempotencyKey", key, "time", Map.of("now", true));
        var first = request("POST", "/api/events", participant.cookie(), input, csrf);
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode event = json.readTree(first.body());
        String id = event.get("id").asText();
        assertThat(event.get("version").asLong()).isEqualTo(1);
        assertThat(json.readTree(request("POST", "/api/events", participant.cookie(), input, csrf).body()).get("id").asText()).isEqualTo(id);
        var forged = new HashMap<String, Object>(input);
        forged.put("owner", TestSupport.DEM);
        assertError(request("POST", "/api/events", participant.cookie(), forged, csrf), 400, "invalid_request");
        assertError(request("POST", "/api/events", participant.cookie(),
                Map.of("type", "SMOKED", "idempotencyKey", key, "time", Map.of("now", true)), csrf), 409, "conflict");
        Login administrator = login(Participants.ALL.getFirst(), Invalid.NONE);
        assertError(request("GET", "/api/events/" + id, administrator.cookie(), null, null), 404, "not_found");
        assertError(request("PATCH", "/api/events/" + id, administrator.cookie(),
                Map.of("version", 1, "type", "SMOKED", "time", Map.of("now", true)), csrf(administrator)), 404, "not_found");
        assertError(request("DELETE", "/api/events/" + id + "?version=1", administrator.cookie(), null, csrf(administrator)), 404, "not_found");
        var update = Map.of("version", 1, "type", "SMOKED", "time", Map.of("now", true));
        var changed = request("PATCH", "/api/events/" + id, participant.cookie(), update, csrf);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(changed.body()).get("version").asLong()).isEqualTo(2);
        assertError(request("PATCH", "/api/events/" + id, participant.cookie(), update, csrf), 409, "conflict");
        assertError(request("DELETE", "/api/events/" + id + "?version=1", participant.cookie(), null, csrf), 409, "conflict");
        assertError(request("DELETE", "/api/events/" + id, participant.cookie(), null, csrf), 400, "invalid_request");
        assertThat(request("DELETE", "/api/events/" + id + "?version=2", participant.cookie(), null, csrf).statusCode()).isEqualTo(204);
        assertError(request("POST", "/api/events", participant.cookie(), input, csrf), 409, "conflict");
        JsonNode insights = json.readTree(request("GET", "/api/insights", administrator.cookie(), null, null).body());
        assertThat(insights.get("group").size()).isEqualTo(3);
        assertThat(insights.get("personal").get("alias").asText()).isEqualTo("Dem");
        assertThat(insights.has("events")).isFalse();
    }

    @Test void zeroAndTimestampEndpointsEnforceCsrfAndValidation() throws Exception {
        Login login = login(Participants.ALL.get(2), Invalid.NONE);
        String csrf = csrf(login);
        String date = "2026-01-01";
        assertThat(request("PUT", "/api/zero/" + date, login.cookie(), null, null).statusCode()).isEqualTo(403);
        assertThat(request("PUT", "/api/zero/" + date, login.cookie(), null, csrf).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", "/api/zero/" + date, login.cookie(), null, null).statusCode()).isEqualTo(403);
        assertThat(request("DELETE", "/api/zero/" + date, login.cookie(), null, csrf).statusCode()).isEqualTo(204);
        assertError(request("PUT", "/api/zero/" + LocalDate.now(TrackingDtos.ROME).plusDays(1), login.cookie(), null, csrf), 400, "invalid_request");
        assertError(request("PUT", "/api/zero/2026-02-30", login.cookie(), null, csrf), 400, "invalid_request");
        for (Map<String, Object> invalidTime : List.<Map<String, Object>>of(
                Map.of("instant", Instant.now().plusSeconds(86400).toString()),
                Map.of("instant", "2025-12-31T00:00:00Z"),
                Map.of("localDateTime", "2026-03-29T02:30:00", "offset", "+01:00"),
                Map.of("localDateTime", "2026-01-01T12:00:00"), Map.of("now", true, "instant", Instant.now().toString()))) {
            assertError(request("POST", "/api/events", login.cookie(),
                    Map.of("type", "SMOKED", "idempotencyKey", UUID.randomUUID().toString(), "time", invalidTime), csrf), 400, "invalid_request");
        }
        assertError(request("POST", "/api/events", login.cookie(),
                Map.of("type", "UNKNOWN", "idempotencyKey", "key", "time", Map.of("now", true)), csrf), 400, "invalid_request");
    }

    @Test void providerOutageDoesNotAffectStorageReadinessOrLivenessAndHealthLeaksNothing() throws Exception {
        ISSUER.outage = true;
        try {
            int before = ISSUER.requests.get();
            for (String endpoint : List.of("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness")) {
                var health = request("GET", endpoint, null, null, null);
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(json.readTree(health.body()).propertyNames()).contains("status").isSubsetOf("status", "groups");
                assertThat(json.readTree(health.body()).get("status").asText()).isEqualTo("UP");
            }
            assertThat(ISSUER.requests.get()).isEqualTo(before);
        } finally { ISSUER.outage = false; }
    }

    @Test void unusableSchemaFailsReadinessButNotLiveness() throws Exception {
        try {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + DIRECTORY.resolve("application.sqlite"));
                 var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=2"); }
            assertThat(request("GET", "/actuator/health/readiness", null, null, null).statusCode()).isEqualTo(503);
            assertThat(request("GET", "/actuator/health/liveness", null, null, null).statusCode()).isEqualTo(200);
        } finally {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + DIRECTORY.resolve("application.sqlite"));
                 var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=1"); }
        }
    }

    private String csrf(Login login) throws Exception {
        return json.readTree(request("GET", "/api/session", login.cookie(), null, null).body()).get("csrfToken").asText();
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
    }

    private record Begin(String cookie, String state) { }
    private record Login(String cookie, String originalCookie, String state, boolean cookieChanged,
                         boolean secureCookie, boolean httpOnlyCookie, boolean laxCookie) { }

    private Begin begin(Participants.Participant participant, Invalid invalid) throws Exception {
        ISSUER.participant = participant;
        ISSUER.invalid = invalid;
        var response = request("GET", "/oauth2/authorization/rage-quit", null, null, null);
        assertThat(response.statusCode()).isEqualTo(302);
        var parameters = parameters(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
        assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
        assertThat(parameters.get("code_challenge")).isNotBlank();
        assertThat(parameters.get("client_id")).isEqualTo("rage-quit");
        assertThat(parameters.get("redirect_uri")).isEqualTo("https://rage-quit.calcifer.tech/login/oauth2/code/rage-quit");
        assertThat(parameters.keySet()).doesNotContain("client_secret", "access_token", "id_token");
        ISSUER.nonce = parameters.get("nonce");
        ISSUER.challenge = parameters.get("code_challenge");
        return new Begin(cookie(response), parameters.get("state"));
    }

    private Login login(Participants.Participant participant, Invalid invalid) throws Exception {
        Begin begin = begin(participant, invalid);
        var response = request("GET", "/login/oauth2/code/rage-quit?code=synthetic&state=" + encode(begin.state()), begin.cookie(), null, null);
        assertThat(response.statusCode()).isEqualTo(302);
        String path = URI.create(response.headers().firstValue("Location").orElseThrow()).getPath();
        assertThat(path).isEqualTo(invalid == Invalid.NONE ? "/index.html" : "/login.html");
        String header = response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("RAGE_QUIT_SESSION="))
                .findFirst().orElse("");
        String current = header.isEmpty() ? begin.cookie() : header.split(";", 2)[0];
        return new Login(current, begin.cookie(), begin.state(), !begin.cookie().equals(current),
                header.contains("Secure"), header.contains("HttpOnly"), header.contains("SameSite=Lax"));
    }

    private HttpResponse<String> request(String method, String path, String cookie, Object body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        // Local tests send secure cookies explicitly over loopback; production browsers use the fixed HTTPS callback.
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("RAGE_QUIT_SESSION="))
                .findFirst().orElseThrow().split(";", 2)[0];
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> result = new HashMap<>();
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static java.nio.file.Path temporaryDirectory() {
        try { return Files.createTempDirectory("rage-quit-security-"); }
        catch (IOException e) { throw new ExceptionInInitializerError("Cannot create temporary test storage"); }
    }

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean @Primary
        ClientRegistrationRepository testClients(RageQuitProperties properties) {
            var production = new SecurityConfiguration().clients(properties).findByRegistrationId("rage-quit");
            return new InMemoryClientRegistrationRepository(ClientRegistration.withClientRegistration(production)
                    .authorizationUri(ISSUER.base() + "/authorize").tokenUri(ISSUER.base() + "/token")
                    .jwkSetUri(ISSUER.base() + "/jwks").userInfoUri(ISSUER.base() + "/userinfo").build());
        }
    }

    private static final class FakeIssuer {
        final HttpServer server;
        final RSAKey key;
        final RSAKey wrongKey;
        final AtomicInteger requests = new AtomicInteger();
        volatile Participants.Participant participant;
        volatile Invalid invalid;
        volatile String nonce;
        volatile String challenge;
        volatile boolean pkceValid;
        volatile boolean basicAuthentication;
        volatile boolean outage;
        final ObjectMapper mapper = new ObjectMapper();

        FakeIssuer() {
            try {
                key = rsaKey();
                wrongKey = rsaKey();
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/jwks", exchange -> respond(exchange, new JWKSet(key.toPublicJWK()).toString()));
                server.createContext("/token", exchange -> {
                    try {
                        var form = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        String verifier = form.get("code_verifier");
                        pkceValid = verifier != null && Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))).equals(challenge);
                        String authentication = exchange.getRequestHeaders().getFirst("Authorization");
                        basicAuthentication = authentication != null && authentication.startsWith("Basic ");
                        if (!pkceValid || !basicAuthentication) { exchange.sendResponseHeaders(400, -1); exchange.close(); return; }
                        respond(exchange, mapper.writeValueAsString(Map.of("access_token", UUID.randomUUID().toString(), "token_type", "Bearer",
                                "expires_in", 300, "scope", "openid profile email", "id_token", token())));
                    } catch (Exception e) { exchange.sendResponseHeaders(500, -1); exchange.close(); }
                });
                server.createContext("/userinfo", exchange -> {
                    var claims = identityClaims();
                    if (invalid == Invalid.USERINFO_SUBJECT) claims.put("sub", "other-subject");
                    respond(exchange, mapper.writeValueAsString(claims));
                });
                server.start();
            } catch (Exception e) { throw new ExceptionInInitializerError("Cannot start synthetic issuer"); }
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private Map<String, Object> identityClaims() {
            Map<String, Object> claims = new HashMap<>();
            if (invalid != Invalid.MISSING_SUBJECT) claims.put("sub", invalid == Invalid.UNKNOWN_SUBJECT ? "unknown-subject" : participant.subject());
            claims.put("email", invalid == Invalid.SWAPPED_EMAIL ? "unknown@example.invalid" : participant.email());
            if (invalid != Invalid.MISSING_VERIFIED) claims.put("email_verified", invalid != Invalid.UNVERIFIED);
            return claims;
        }

        private String token() throws Exception {
            if (invalid == Invalid.MALFORMED) return "malformed";
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer(invalid == Invalid.WRONG_ISSUER ? "https://other.invalid" : "https://auth.calcifer.tech")
                    .audience(invalid == Invalid.WRONG_AUDIENCE ? "other-client" : "rage-quit")
                    .issueTime(Date.from(now.minusSeconds(10)))
                    .claim("nonce", invalid == Invalid.WRONG_NONCE ? "wrong-nonce" : nonce);
            if (invalid != Invalid.MISSING_EXPIRY) claims.expirationTime(Date.from(now.plusSeconds(invalid == Invalid.EXPIRED ? -1 : 300)));
            if (invalid == Invalid.MULTI_AUDIENCE_MISSING_AZP || invalid == Invalid.WRONG_AZP) {
                claims.audience(List.of("rage-quit", "other-client"));
                if (invalid == Invalid.WRONG_AZP) claims.claim("azp", "other-client");
            }
            identityClaims().forEach(claims::claim);
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic").build(), claims.build());
            jwt.sign(new RSASSASigner(invalid == Invalid.BAD_SIGNATURE ? wrongKey : key));
            return jwt.serialize();
        }

        private void respond(HttpExchange exchange, String body) throws IOException {
            requests.incrementAndGet();
            if (outage) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
            exchange.close();
        }

        private static RSAKey rsaKey() throws Exception {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).keyID("synthetic").build();
        }
    }
}
