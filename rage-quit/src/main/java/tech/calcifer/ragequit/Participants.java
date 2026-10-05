package tech.calcifer.ragequit;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.List;

public final class Participants {
    public record Participant(String subject, String email, String alias) { }

    public static final List<Participant> ALL = List.of(
            new Participant("user:admin", "dem.gianluigi@gmail.com", "Dem"),
            new Participant("user:moody", "pugliens@gmail.com", "Pugliens"),
            new Participant("user:frevadiscor", "frevadiscor@gmail.com", "Frevadiscor"));

    private Participants() { }

    public static Participant requireSubject(String subject) {
        return ALL.stream().filter(p -> p.subject().equals(subject)).findFirst()
                .orElseThrow(Participants::denied);
    }

    public static Participant requireIdentity(OidcUser user) {
        if (user == null || !Boolean.TRUE.equals(user.getEmailVerified())) {
            throw denied();
        }
        Participant participant = requireSubject(user.getSubject());
        if (user.getEmail() == null || !participant.email().equalsIgnoreCase(user.getEmail())) {
            throw denied();
        }
        return participant;
    }

    private static OAuth2AuthenticationException denied() {
        return new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "Identity not admitted");
    }
}
