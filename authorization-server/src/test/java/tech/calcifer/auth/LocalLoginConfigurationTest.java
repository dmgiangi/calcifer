package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Map;
import java.util.Set;


class LocalLoginConfigurationTest {

    private static final IdentityProperties.Client GRAFANA = new IdentityProperties.Client(
        "grafana",
        "secret",
        "https://grafana.calcifer.tech/login/generic_oauth",
        "grafana"
    );
    private static final IdentityProperties.Client API = new IdentityProperties.Client(
        "grafana-api",
        "secret",
        "https://grafana.calcifer.tech",
        "grafana"
    );

    @Test
    void refusesEnabledLocalLoginWithoutHash() {
        IdentityProperties properties = properties("");
        assertThatThrownBy(() -> new AuthorizationServerConfiguration().localAdministrator(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Local login requires AUTH_LOCAL_LOGIN_PASSWORD_HASH");
    }

    @Test
    void acceptsBcryptHashWithoutExposingPlaintext() {
        String hash = new BCryptPasswordEncoder().encode("test-password");
        var service = new AuthorizationServerConfiguration().localAdministrator(properties(hash));
        assertThat(service.loadUserByUsername("dem.gianluigi@gmail.com").getPassword()).isEqualTo("{bcrypt}" + hash);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{bcrypt}"})
    void productionProviderAuthenticatesBcryptHashes(String prefix) {
        var configuration = new AuthorizationServerConfiguration();
        String hash = prefix + new BCryptPasswordEncoder().encode("test-password");
        var provider = configuration.localPasswordAuthenticationProvider(properties(hash), configuration.passwordEncoder());

        assertThat(provider.authenticate(new UsernamePasswordAuthenticationToken(
            "dem.gianluigi@gmail.com", "test-password"
        )).isAuthenticated()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{argon2}", "{argon2@SpringSecurity_v5_8}"})
    void productionProviderAuthenticatesArgon2idHashes(String prefix) {
        var configuration = new AuthorizationServerConfiguration();
        String hash = prefix + Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("test-password");
        var provider = configuration.localPasswordAuthenticationProvider(properties(hash), configuration.passwordEncoder());

        assertThat(provider.authenticate(new UsernamePasswordAuthenticationToken(
            "dem.gianluigi@gmail.com", "test-password"
        )).isAuthenticated()).isTrue();
        assertThatThrownBy(() -> provider.authenticate(new UsernamePasswordAuthenticationToken(
            "dem.gianluigi@gmail.com", "wrong-password"
        ))).isInstanceOf(BadCredentialsException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test-only", "{noop}test-only", "{pbkdf2}test-only", "{scrypt}test-only",
        "{MD5}test-only", "{SHA-256}test-only", "{bcrypt}test-only", "{argon2}test-only", "{bcrypt"})
    void rejectsUnsupportedOrMalformedHashWithoutDisclosingIt(String hash) {
        assertThatThrownBy(() -> new AuthorizationServerConfiguration().localAdministrator(properties(hash)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Local login password hash must use bcrypt or Argon2id with a supported encoding");
    }

    @ParameterizedTest
    @ValueSource(strings = {"argon2i", "argon2d"})
    void rejectsOtherArgon2VariantsEvenWithAllowedSpringPrefix(String variant) {
        String hash = "{argon2}" + Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
            .encode("test-password").replace("$argon2id$", "$" + variant + "$");
        assertThatThrownBy(() -> new AuthorizationServerConfiguration().localAdministrator(properties(hash)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Local login password hash must use bcrypt or Argon2id with a supported encoding");
    }

    @Test
    void rejectsMismatchedEncodingPrefixAndHashPayload() {
        var configuration = new AuthorizationServerConfiguration();
        String bcryptHash = new BCryptPasswordEncoder().encode("test-password");
        String argon2Hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("test-password");
        for (String hash : new String[]{"{argon2}" + bcryptHash, "{bcrypt}" + argon2Hash, "{noop}" + bcryptHash}) {
            assertThatThrownBy(() -> configuration.localAdministrator(properties(hash)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local login password hash must use bcrypt or Argon2id with a supported encoding");
        }
    }

    @Test
    void disabledLocalLoginDoesNotRequireAHash() {
        IdentityProperties properties = new IdentityProperties(
            "https://auth.calcifer.tech", "dem.gianluigi@gmail.com", "user:admin", "file:key", GRAFANA, API,
            new IdentityProperties.LocalLogin(false, "dem.gianluigi@gmail.com", "")
        );
        var service = new AuthorizationServerConfiguration().localAdministrator(properties);
        assertThatThrownBy(() -> service.loadUserByUsername("dem.gianluigi@gmail.com"))
            .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
    }

    @Test
    void rejectsBadPassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        DaoAuthenticationProvider provider
            =
            new DaoAuthenticationProvider(new AuthorizationServerConfiguration().localAdministrator(properties(encoder.encode(
            "correct-password"))));
        provider.setPasswordEncoder(new AuthorizationServerConfiguration().passwordEncoder());
        assertThatThrownBy(() -> provider.authenticate(new UsernamePasswordAuthenticationToken(
            "dem.gianluigi@gmail.com",
            "wrong-password"
        ))).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void explicitLocalProviderAuthenticatesTheConfiguredUser() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        var configuration = new AuthorizationServerConfiguration();
        var provider = configuration.localPasswordAuthenticationProvider(
            properties(encoder.encode("correct-password")),
            configuration.passwordEncoder()
        );

        var authentication = provider.authenticate(new UsernamePasswordAuthenticationToken(
            "dem.gianluigi@gmail.com",
            "correct-password"
        ));

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("dem.gianluigi@gmail.com");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).contains("ROLE_ADMIN");
    }

    @Test
    void rejectsPasswordForGoogleOnlyCatalogUserWhenLocalLoginIsEnabledForAdmin() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        IdentityProperties properties = new IdentityProperties(
            "https://auth.calcifer.tech",
            "file:key",
            Set.of("admin", "rage-quit"),
            Map.of(
                "administrator", new IdentityProperties.User(
                    "dem.gianluigi@gmail.com", "user:admin", Set.of("admin"), Set.of("google", "password")
                ),
                "pugliens", new IdentityProperties.User(
                    "pugliens@gmail.com", "user:pugliens", Set.of("rage-quit"), Set.of("google")
                )
            ),
            GRAFANA,
            API,
            new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", encoder.encode("correct-password")),
            Map.of()
        );
        var configuration = new AuthorizationServerConfiguration();
        var provider = configuration.localPasswordAuthenticationProvider(properties, configuration.passwordEncoder());

        assertThatThrownBy(() -> provider.authenticate(new UsernamePasswordAuthenticationToken(
            "pugliens@gmail.com",
            "correct-password"
        ))).isInstanceOf(BadCredentialsException.class);
    }

    private static IdentityProperties properties(String hash) {
        return new IdentityProperties(
            "https://auth.calcifer.tech",
            "dem.gianluigi@gmail.com",
            "user:admin",
            "file:key",
            GRAFANA,
            API,
            new IdentityProperties.LocalLogin(true, "dem.gianluigi@gmail.com", hash)
        );
    }
}
