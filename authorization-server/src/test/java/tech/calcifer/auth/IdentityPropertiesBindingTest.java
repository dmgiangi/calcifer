package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;


class IdentityPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "identity.issuer=https://auth.calcifer.tech",
            "identity.signing-key-location=file:key",
            "identity.groups[0]=admin",
            "identity.groups[1]=rage-quit",
            "identity.users.administrator.email=admin@example.com",
            "identity.users.administrator.canonical-subject=user:admin",
            "identity.users.administrator.groups[0]=admin",
            "identity.users.administrator.authentication-methods[0]=google",
            "identity.users.administrator.authentication-methods[1]=password",
            "identity.users.pugliens.email=pugliens@gmail.com",
            "identity.users.pugliens.canonical-subject=user:moody",
            "identity.users.pugliens.groups[0]=rage-quit",
            "identity.users.pugliens.authentication-methods[0]=google",
            "identity.grafana.id=grafana",
            "identity.grafana.secret=grafana-secret",
            "identity.grafana.redirect-uri=https://grafana.calcifer.tech/login/generic_oauth",
            "identity.grafana.audience=grafana",
            "identity.grafana-api.id=grafana-api",
            "identity.grafana-api.secret=grafana-api-secret",
            "identity.grafana-api.redirect-uri=https://grafana.calcifer.tech",
            "identity.grafana-api.audience=grafana",
            "identity.local-login.enabled=false",
            "identity.local-login.username=admin@example.com",
            "HOMEPAGE_OIDC_CLIENT_SECRET=resolved-secret",
            "HOME_ASSISTANT_OIDC_CLIENT_SECRET=home-assistant-secret",
            "identity.clients.homepage.id=homepage",
            "identity.clients.homepage.secret=${HOMEPAGE_OIDC_CLIENT_SECRET}",
            "identity.clients.homepage.redirect-uris[0]=https://calcifer.tech/api/auth/callback/homepage-oidc",
            "identity.clients.homepage.scopes[0]=openid",
            "identity.clients.homepage.scopes[1]=email",
            "identity.clients.homepage.grant-types[0]=authorization_code",
            "identity.clients.homepage.authentication-methods[0]=client_secret_post",
            "identity.clients.homepage.audience=homepage",
            "identity.clients.homepage.require-proof-key=true",
            "identity.clients.homepage.access-token-ttl=2m",
            "identity.clients.home-assistant.id=home-assistant",
            "identity.clients.home-assistant.secret=${HOME_ASSISTANT_OIDC_CLIENT_SECRET}",
            "identity.clients.home-assistant.redirect-uris[0]=https://home.calcifer.tech/auth/oidc/callback",
            "identity.clients.home-assistant.scopes[0]=openid",
            "identity.clients.home-assistant.scopes[1]=profile",
            "identity.clients.home-assistant.scopes[2]=email",
            "identity.clients.home-assistant.grant-types[0]=authorization_code",
            "identity.clients.home-assistant.authentication-methods[0]=client_secret_post",
            "identity.clients.home-assistant.require-proof-key=true",
            "identity.clients.rage-quit.id=rage-quit",
            "identity.clients.rage-quit.secret=rage-quit-secret",
            "identity.clients.rage-quit.redirect-uris[0]=https://rage-quit.calcifer.tech/login/oauth2/code/auth",
            "identity.clients.rage-quit.scopes[0]=openid",
            "identity.clients.rage-quit.grant-types[0]=authorization_code",
            "identity.clients.rage-quit.allowed-groups[0]=rage-quit"
        );

    @Test
    void resolvesSecretPlaceholderAndBindsDeclarativeClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var homepage = context.getBean(IdentityProperties.class).clients().get("homepage");
            assertThat(homepage.secret()).isEqualTo("resolved-secret");
            assertThat(homepage.accessTokenTtl()).hasMinutes(2);
            assertThat(homepage.requireProofKey()).isTrue();
            var homeAssistant = context.getBean(IdentityProperties.class).clients().get("home-assistant");
            assertThat(homeAssistant.secret()).isEqualTo("home-assistant-secret");
            assertThat(homeAssistant.redirectUris()).containsExactly("https://home.calcifer.tech/auth/oidc/callback");
            assertThat(homeAssistant.requireProofKey()).isTrue();
            assertThat(context.getBean(IdentityProperties.class).clients().get("rage-quit").allowedGroups())
                .containsExactly("rage-quit");
            assertThat(context.getBean(IdentityProperties.class).userByEmail("PUGLIENS@gmail.com"))
                .isEqualTo(new IdentityProperties.User(
                    "pugliens@gmail.com",
                    "user:moody",
                    java.util.Set.of("rage-quit"),
                    java.util.Set.of("google")
                ));
        });
    }

    @Test
    void rejectsUnknownGroupsAndUnsupportedAuthenticationMethods() {
        contextRunner.withPropertyValues(
            "identity.users.pugliens.groups[0]=unknown",
            "identity.users.pugliens.authentication-methods[0]=magic"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsDuplicateCanonicalSubjects() {
        contextRunner.withPropertyValues(
            "identity.users.pugliens.canonical-subject=user:admin"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsCanonicalSubjectMethodAndExplicitRolesWithoutChangingLegacyClients() {
        contextRunner.withPropertyValues(
            "identity.clients.rage-quit.allowed-subjects[0]=user:moody",
            "identity.clients.rage-quit.required-authentication-method=google",
            "identity.users.pugliens.roles[0]=rage-quit-user"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(IdentityProperties.class);
            assertThat(properties.clients().get("rage-quit").allowedSubjects()).containsExactly("user:moody");
            assertThat(properties.clients().get("rage-quit").requiredAuthenticationMethod()).isEqualTo("google");
            assertThat(properties.users().get("pugliens").effectiveRoles()).containsExactly("rage-quit-user");
            assertThat(properties.clients().get("homepage").effectiveAllowedGroups()).containsExactly("admin");
        });
    }

    @Test
    void rejectsUnknownClientSubjectsSourcesGroupsAndPrivilegeEscalation() {
        for (String invalid : java.util.List.of(
            "identity.clients.rage-quit.allowed-subjects[0]=user:unknown",
            "identity.clients.rage-quit.required-authentication-method=magic",
            "identity.clients.rage-quit.required-authentication-method=",
            "identity.clients.rage-quit.allowed-groups[0]=unknown",
            "identity.users.pugliens.roles[0]=admin",
            "identity.clients.rage-quit.secret=REPLACE_WITH_SECRET",
            "identity.clients.rage-quit.secret=change-me")) {
            contextRunner.withPropertyValues(invalid).run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class PropertiesConfiguration {}
}
