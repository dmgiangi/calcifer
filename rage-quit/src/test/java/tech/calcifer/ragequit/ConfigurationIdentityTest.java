package tech.calcifer.ragequit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static tech.calcifer.ragequit.TestSupport.*;

class ConfigurationIdentityTest {
    @TempDir Path directory;

    static Stream<Participants.Participant> participants() { return Participants.ALL.stream(); }

    @Test void participantSubjectsRemainStableAndIndependentOfTheApplicationName() {
        assertThat(Participants.ALL).containsExactly(
                new Participants.Participant("user:admin", "dem.gianluigi@gmail.com", "Dem"),
                new Participants.Participant("user:moody", "pugliens@gmail.com", "Pugliens"),
                new Participants.Participant("user:frevadiscor", "frevadiscor@gmail.com", "Frevadiscor"));
    }

    @ParameterizedTest @MethodSource("participants")
    void registryAcceptsOnlyMatchingVerifiedCanonicalIdentities(Participants.Participant participant) {
        assertThat(Participants.requireIdentity(user(participant.subject(), participant.email().toUpperCase(), true)))
                .isEqualTo(participant);
        assertThatThrownBy(() -> Participants.requireIdentity(user(participant.subject(), participant.email(), false)))
                .hasMessage("Identity not admitted");
        assertThatThrownBy(() -> Participants.requireIdentity(user(participant.subject(), "unknown@example.invalid", true)))
                .hasMessage("Identity not admitted");
        assertThatThrownBy(() -> Participants.requireIdentity(user("provider-subject", participant.email(), true)))
                .hasMessage("Identity not admitted");
    }

    @Test void unknownMissingAndSwappedIdentitiesFailAndRegistryIsImmutable() {
        assertThatThrownBy(() -> Participants.requireIdentity(user(DEM, "pugliens@gmail.com", true))).hasMessage("Identity not admitted");
        assertThatThrownBy(() -> Participants.requireIdentity(user(DEM, null, true))).hasMessage("Identity not admitted");
        assertThatThrownBy(() -> Participants.requireIdentity(user(DEM, "dem.gianluigi@gmail.com", null))).hasMessage("Identity not admitted");
        assertThatThrownBy(() -> Participants.requireIdentity(null)).hasMessage("Identity not admitted");
        assertThatThrownBy(() -> Participants.ALL.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void strictConfigurationRejectsMissingOrUnexpectedValues() {
        Path file = directory.resolve("records.sqlite");
        for (String issuer : new String[]{null, "", "http://auth.calcifer.tech", "https://other.invalid"}) {
            assertThatThrownBy(() -> new RageQuitProperties(issuer, "rage-quit", "synthetic-test-fixture", file, START))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String client : new String[]{null, "", "other"}) {
            assertThatThrownBy(() -> new RageQuitProperties("https://auth.calcifer.tech", client, "synthetic-test-fixture", file, START))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String secret : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> new RageQuitProperties("https://auth.calcifer.tech", "rage-quit", secret, file, START))
                    .hasMessage("RAGE_QUIT_CLIENT_SECRET is required");
        }
        for (String secret : new String[]{"REPLACE_WITH_SECRET", "replace-me", "CHANGE_ME", "placeholder", "example"}) {
            assertThatThrownBy(() -> new RageQuitProperties("https://auth.calcifer.tech", "rage-quit", secret, file, START))
                    .hasMessage("RAGE_QUIT_CLIENT_SECRET must not be a placeholder");
        }
        for (Path path : new Path[]{null, Path.of("relative.sqlite"), Path.of("/")}) {
            assertThatThrownBy(() -> new RageQuitProperties("https://auth.calcifer.tech", "rage-quit", "synthetic-test-fixture", path, START))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RageQuitProperties("https://auth.calcifer.tech", "rage-quit", "synthetic-test-fixture", file, null))
                .hasMessage("RAGE_QUIT_START_DATE is required");
        assertThatThrownBy(() -> LocalDate.parse("2026-02-30")).isInstanceOf(java.time.format.DateTimeParseException.class);
        assertThat(new RageQuitProperties("https://auth.calcifer.tech", "rage-quit", "synthetic-test-fixture", file, START).toString())
                .isEqualTo("RageQuitProperties[redacted]");
    }

    @Test void bindingRequiresEveryOperatorSettingAndRejectsInvalidCalendarDates() {
        Map<String, Object> valid = Map.of("rage-quit.issuer", "https://auth.calcifer.tech", "rage-quit.client-id", "rage-quit",
                "rage-quit.client-secret", "synthetic-test-fixture", "rage-quit.database", directory.resolve("bind.sqlite").toString(),
                "rage-quit.start-date", "2026-03-28");
        assertThat(new Binder(new MapConfigurationPropertySource(valid)).bind("rage-quit", Bindable.of(RageQuitProperties.class)).get().startDate())
                .isEqualTo(START);
        for (String key : valid.keySet()) {
            var missing = new HashMap<>(valid);
            missing.remove(key);
            assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(missing)).bind("rage-quit", Bindable.of(RageQuitProperties.class)))
                    .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
        }
        for (String secret : new String[]{"REPLACE_WITH_SECRET", "replace-me"}) {
            var placeholder = new HashMap<>(valid);
            placeholder.put("rage-quit.client-secret", secret);
            assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(placeholder))
                    .bind("rage-quit", Bindable.of(RageQuitProperties.class)))
                    .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
        }
        var invalid = new HashMap<>(valid);
        invalid.put("rage-quit.start-date", "2026-02-30");
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(invalid)).bind("rage-quit", Bindable.of(RageQuitProperties.class)))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }

    private static DefaultOidcUser user(String subject, String email, Boolean verified) {
        var claims = new HashMap<String, Object>();
        claims.put("sub", subject);
        if (email != null) claims.put("email", email);
        if (verified != null) claims.put("email_verified", verified);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT")),
                new OidcIdToken("synthetic", NOW.minusSeconds(10), NOW.plusSeconds(100), claims));
    }
}
