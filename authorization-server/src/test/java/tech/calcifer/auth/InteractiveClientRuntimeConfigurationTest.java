package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class InteractiveClientRuntimeConfigurationTest {

    private ApplicationContextRunner runner() {
        var values = new ArrayList<String>();
        var properties = InteractiveClientTestSupport.properties();
        values.add("identity.issuer=" + properties.issuer());
        values.add("identity.signing-key-location=file:synthetic-test-key");
        addIndexed(values, "identity.groups", properties.groups());
        values.add("identity.local-login.enabled=false");
        values.add("identity.local-login.username=" + InteractiveClientTestSupport.ADMIN);
        for (String name : List.of("grafana", "grafana-api")) {
            values.add("identity." + name + ".id=" + name);
            values.add("identity." + name + ".secret=test-legacy-secret");
            values.add("identity." + name + ".audience=grafana");
            values.add("identity." + name + ".redirect-uri=https://grafana.example.test/login/generic_oauth");
        }
        properties.users().forEach((key, user) -> {
            String prefix = "identity.users." + key;
            values.add(prefix + ".email=" + user.email());
            values.add(prefix + ".canonical-subject=" + user.canonicalSubject());
            addIndexed(values, prefix + ".groups", user.groups());
            values.add(prefix + ".authentication-methods[0]=google");
            addIndexed(values, prefix + ".roles", user.effectiveRoles());
        });
        properties.clients().forEach((key, client) -> {
            String prefix = "identity.clients." + key;
            values.add(prefix + ".id=" + client.id());
            values.add(prefix + ".secret=" + (key.equals("restricted-client")
                ? "${SYNTHETIC_CLIENT_SECRET:}" : client.secret()));
            addIndexed(values, prefix + ".redirect-uris", client.redirectUris());
            addIndexed(values, prefix + ".scopes", client.scopes());
            addIndexed(values, prefix + ".grant-types", client.grantTypes());
            addIndexed(values, prefix + ".authentication-methods", client.authenticationMethods());
            values.add(prefix + ".audience=" + client.effectiveAudience());
            values.add(prefix + ".require-proof-key=" + client.requireProofKey());
        });
        addIndexed(values, "identity.clients.restricted-client.allowed-subjects", Set.copyOf(InteractiveClientTestSupport.SUBJECTS.values()));
        values.add("identity.clients.restricted-client.required-authentication-method=google");
        return new ApplicationContextRunner()
            .withUserConfiguration(IdentityPropertiesBindingTest.PropertiesConfiguration.class)
            .withPropertyValues(values.toArray(String[]::new));
    }

    private static void addIndexed(List<String> values, String prefix, Set<String> entries) {
        int index = 0;
        for (String entry : entries) {
            values.add(prefix + "[" + index++ + "]=" + entry);
        }
    }

    @Test
    void syntheticRegistrationBindsTheExactInteractiveContract() {
        runner().withPropertyValues("SYNTHETIC_CLIENT_SECRET=test-client-secret").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(IdentityProperties.class);
            var client = properties.clients().get("restricted-client");
            assertThat(client.redirectUris()).containsExactly(InteractiveClientTestSupport.CALLBACK);
            assertThat(client.scopes()).containsExactlyInAnyOrder("openid", "profile", "email");
            assertThat(client.grantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
            assertThat(client.authenticationMethods()).containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue());
            assertThat(client.requireProofKey()).isTrue();
            assertThat(client.effectiveAudience()).isEqualTo("restricted-client");
            assertThat(client.allowedSubjects()).containsExactlyInAnyOrderElementsOf(InteractiveClientTestSupport.SUBJECTS.values());
            assertThat(client.requiredAuthenticationMethod()).isEqualTo("google");
            assertThat(client.secret()).isEqualTo("test-client-secret");
            assertThat(properties.users().get("alice").effectiveRoles()).containsExactly("participant");
            assertThat(properties.users().get("bob").effectiveRoles()).containsExactly("participant");
            assertThat(properties.users().get("administrator").effectiveRoles()).containsExactly("admin");
        });
    }

    @Test
    void missingSecretAndMissingCatalogParticipantFailClosed() {
        runner().withPropertyValues("SYNTHETIC_CLIENT_SECRET=")
            .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("SYNTHETIC_CLIENT_SECRET=test-client-secret",
            "identity.users.alice.canonical-subject=user:other")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void wildcardCallbackConfigurationFailsRegistration() {
        runner().withPropertyValues("SYNTHETIC_CLIENT_SECRET=test-client-secret",
            "identity.clients.restricted-client.redirect-uris[0]=https://client.example.test/*").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> new InteractiveClientOidcFlowTest.TestConfiguration()
                .clients(context.getBean(IdentityProperties.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact and absolute");
        });
    }
}