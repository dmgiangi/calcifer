package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class RageQuitRuntimeConfigurationTest {

    private ApplicationContextRunner runner() {
        var values = new ArrayList<String>();
        var properties = RageQuitTestSupport.properties();
        values.add("identity.issuer=" + properties.issuer());
        values.add("identity.signing-key-location=file:synthetic-test-key");
        values.add("identity.groups[0]=admin");
        values.add("identity.groups[1]=rage-quit");
        values.add("identity.local-login.enabled=false");
        values.add("identity.local-login.username=" + RageQuitTestSupport.ADMIN);
        for (String name : java.util.List.of("grafana", "grafana-api")) {
            values.add("identity." + name + ".id=" + name);
            values.add("identity." + name + ".secret=test-secret");
            values.add("identity." + name + ".audience=grafana");
            values.add("identity." + name + ".redirect-uri=https://grafana.calcifer.tech/login/generic_oauth");
        }
        properties.users().forEach((key, user) -> {
            String prefix = "identity.users." + key;
            values.add(prefix + ".email=" + user.email());
            values.add(prefix + ".canonical-subject=" + user.canonicalSubject());
            values.add(prefix + ".groups[0]=" + user.groups().iterator().next());
            values.add(prefix + ".authentication-methods[0]=google");
        });
        return new ApplicationContextRunner()
            .withUserConfiguration(IdentityPropertiesBindingTest.PropertiesConfiguration.class)
            .withPropertyValues(values.toArray(String[]::new))
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader().load("rage-quit", new ClassPathResource("application-rage-quit.yaml"))
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
    }

    @Test
    void defaultNonSecretRegistrationBindsTheExactSharedContract() {
        runner().withPropertyValues("AUTH_CLIENT_RAGE_QUIT_SECRET=synthetic-rage-secret").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(IdentityProperties.class);
            var client = properties.clients().get("rage-quit");
            assertThat(client.redirectUris()).containsExactly(RageQuitTestSupport.CALLBACK);
            assertThat(client.scopes()).containsExactlyInAnyOrder("openid", "profile", "email");
            assertThat(client.grantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
            assertThat(client.authenticationMethods()).containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue());
            assertThat(client.requireProofKey()).isTrue();
            assertThat(client.effectiveAudience()).isEqualTo("rage-quit");
            assertThat(client.allowedSubjects()).containsExactlyInAnyOrderElementsOf(RageQuitTestSupport.SUBJECTS.values());
            assertThat(client.requiredAuthenticationMethod()).isEqualTo("google");
            assertThat(client.secret()).isEqualTo("synthetic-rage-secret");
            assertThat(properties.users().get("pugliens").effectiveRoles()).containsExactly("rage-quit-user");
            assertThat(properties.users().get("frevadiscor").effectiveRoles()).containsExactly("rage-quit-user");
            assertThat(properties.users().get("administrator").effectiveRoles()).containsExactly("admin");
        });
    }

    @Test
    void missingSecretAndMissingCatalogParticipantFailClosed() {
        runner().withPropertyValues("AUTH_CLIENT_RAGE_QUIT_SECRET=")
            .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("AUTH_CLIENT_RAGE_QUIT_SECRET=synthetic-rage-secret",
            "identity.users.pugliens.canonical-subject=user:other")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void wildcardCallbackConfigurationFailsRegistration() {
        runner().withPropertyValues("AUTH_CLIENT_RAGE_QUIT_SECRET=synthetic-rage-secret",
            "identity.clients.rage-quit.redirect-uris[0]=https://rage-quit.calcifer.tech/*").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> new RageQuitOidcFlowTest.TestConfiguration()
                .clients(context.getBean(IdentityProperties.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact and absolute");
        });
    }
}