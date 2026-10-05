package tech.calcifer.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;

public final class RageQuitTestSupport {

    public static final String CALLBACK = "https://rage-quit.calcifer.tech/login/oauth2/code/rage-quit";
    public static final String ADMIN = "dem.gianluigi@gmail.com";
    public static final Map<String, String> SUBJECTS = Map.of(
        ADMIN, "user:admin", "pugliens@gmail.com", "user:moody",
        "frevadiscor@gmail.com", "user:frevadiscor");

    private RageQuitTestSupport() {}

    public static IdentityProperties properties() {
        var base = InteractiveClientGroupPolicyTest.properties();
        Map<String, IdentityProperties.User> users = new HashMap<>();
        SUBJECTS.forEach((email, subject) -> users.put(email.equals(ADMIN) ? "administrator" : email.split("@")[0],
            new IdentityProperties.User(email, subject, Set.of(email.equals(ADMIN) ? "admin" : "rage-quit"),
                email.equals(ADMIN) ? Set.of("google", "password") : Set.of("google"),
                Set.of(email.equals(ADMIN) ? "admin" : "rage-quit-user"))));
        Map<String, IdentityProperties.ClientDefinition> clients = new HashMap<>();
        clients.put("rage-quit", new IdentityProperties.ClientDefinition("rage-quit", "test-rage-secret",
            Set.of(CALLBACK), Set.of("openid", "profile", "email"), Set.of("authorization_code"),
            Set.of("client_secret_basic"), "rage-quit", true, null, Set.of(), Set.copyOf(SUBJECTS.values()), "google"));
        for (String id : Set.of("homepage", "home-assistant", "zigbee2mqtt")) {
            clients.put(id, new IdentityProperties.ClientDefinition(id, "test-other-secret",
                Set.of("https://" + id + ".example.test/callback"), Set.of("openid", "profile", "email"),
                Set.of("authorization_code"), Set.of("client_secret_basic"), id, true, null));
        }
        return new IdentityProperties(base.issuer(), base.signingKeyLocation(), base.groups(), users,
            base.grafana(), base.grafanaApi(), base.localLogin(), clients);
    }

    public static Authentication google(String email) {
        return IdentityTestAuthentications.google(email);
    }

    public static Authentication password() {
        return IdentityTestAuthentications.password(ADMIN);
    }

    static IdentityProperties withClient(IdentityProperties.ClientDefinition client) {
        var base = properties();
        return new IdentityProperties(base.issuer(), base.signingKeyLocation(), base.groups(), base.users(),
            base.grafana(), base.grafanaApi(), base.localLogin(), Map.of(client.id(), client));
    }
}
