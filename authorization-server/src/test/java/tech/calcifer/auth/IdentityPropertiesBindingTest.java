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
            "identity.issuer=https://auth.example.test",
            "identity.signing-key-location=file:key",
            "identity.groups[0]=admin",
            "identity.groups[1]=participants",
            "identity.users.administrator.email=admin@example.test",
            "identity.users.administrator.canonical-subject=user:admin",
            "identity.users.administrator.groups[0]=admin",
            "identity.users.administrator.authentication-methods[0]=google",
            "identity.users.administrator.authentication-methods[1]=password",
            "identity.users.alice.email=alice@example.test",
            "identity.users.alice.canonical-subject=user:alice",
            "identity.users.alice.groups[0]=participants",
            "identity.users.alice.authentication-methods[0]=google",
            "identity.grafana.id=grafana",
            "identity.grafana.secret=grafana-secret",
            "identity.grafana.redirect-uri=https://grafana.example.test/login/generic_oauth",
            "identity.grafana.audience=grafana",
            "identity.grafana-api.id=grafana-api",
            "identity.grafana-api.secret=grafana-api-secret",
            "identity.grafana-api.redirect-uri=https://grafana.example.test",
            "identity.grafana-api.audience=grafana",
            "identity.local-login.enabled=false",
            "identity.local-login.username=admin@example.test",
            "PORTAL_TEST_SECRET=resolved-test-secret",
            "REPORTS_TEST_SECRET=test-reports-secret",
            "identity.clients.portal.id=portal",
            "identity.clients.portal.secret=${PORTAL_TEST_SECRET}",
            "identity.clients.portal.redirect-uris[0]=https://portal.example.test/callback",
            "identity.clients.portal.scopes[0]=openid",
            "identity.clients.portal.scopes[1]=email",
            "identity.clients.portal.grant-types[0]=authorization_code",
            "identity.clients.portal.authentication-methods[0]=client_secret_post",
            "identity.clients.portal.audience=portal",
            "identity.clients.portal.require-proof-key=true",
            "identity.clients.portal.access-token-ttl=2m",
            "identity.clients.reports.id=reports",
            "identity.clients.reports.secret=${REPORTS_TEST_SECRET}",
            "identity.clients.reports.redirect-uris[0]=https://reports.example.test/callback",
            "identity.clients.reports.scopes[0]=openid",
            "identity.clients.reports.scopes[1]=profile",
            "identity.clients.reports.scopes[2]=email",
            "identity.clients.reports.grant-types[0]=authorization_code",
            "identity.clients.reports.authentication-methods[0]=client_secret_post",
            "identity.clients.reports.require-proof-key=true",
            "identity.clients.restricted-client.id=restricted-client",
            "identity.clients.restricted-client.secret=test-client-secret",
            "identity.clients.restricted-client.redirect-uris[0]=https://client.example.test/login/oauth2/code/auth",
            "identity.clients.restricted-client.scopes[0]=openid",
            "identity.clients.restricted-client.grant-types[0]=authorization_code",
            "identity.clients.restricted-client.allowed-groups[0]=participants"
        );

    @Test
    void resolvesSecretPlaceholderAndBindsDeclarativeClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var portal = context.getBean(IdentityProperties.class).clients().get("portal");
            assertThat(portal.secret()).isEqualTo("resolved-test-secret");
            assertThat(portal.accessTokenTtl()).hasMinutes(2);
            assertThat(portal.requireProofKey()).isTrue();
            var reports = context.getBean(IdentityProperties.class).clients().get("reports");
            assertThat(reports.secret()).isEqualTo("test-reports-secret");
            assertThat(reports.redirectUris()).containsExactly("https://reports.example.test/callback");
            assertThat(reports.requireProofKey()).isTrue();
            assertThat(context.getBean(IdentityProperties.class).clients().get("restricted-client").allowedGroups())
                .containsExactly("participants");
            assertThat(context.getBean(IdentityProperties.class).userByEmail("ALICE@example.test"))
                .isEqualTo(new IdentityProperties.User(
                    "alice@example.test",
                    "user:alice",
                    java.util.Set.of("participants"),
                    java.util.Set.of("google")
                ));
        });
    }

    @Test
    void rejectsUnknownGroupsAndUnsupportedAuthenticationMethods() {
        contextRunner.withPropertyValues(
            "identity.users.alice.groups[0]=unknown",
            "identity.users.alice.authentication-methods[0]=magic"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsDuplicateCanonicalSubjects() {
        contextRunner.withPropertyValues(
            "identity.users.alice.canonical-subject=user:admin"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsCanonicalSubjectMethodAndExplicitRolesWithoutChangingLegacyClients() {
        contextRunner.withPropertyValues(
            "identity.clients.restricted-client.allowed-subjects[0]=user:alice",
            "identity.clients.restricted-client.required-authentication-method=google",
            "identity.users.alice.roles[0]=participant"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(IdentityProperties.class);
            assertThat(properties.clients().get("restricted-client").allowedSubjects()).containsExactly("user:alice");
            assertThat(properties.clients().get("restricted-client").requiredAuthenticationMethod()).isEqualTo("google");
            assertThat(properties.users().get("alice").effectiveRoles()).containsExactly("participant");
            assertThat(properties.clients().get("portal").effectiveAllowedGroups()).containsExactly("admin");
        });
    }

    @Test
    void rejectsInvalidClientPoliciesPrivilegeEscalationAndBlankSecrets() {
        for (String invalid : java.util.List.of(
            "identity.clients.restricted-client.allowed-subjects[0]=user:unknown",
            "identity.clients.restricted-client.required-authentication-method=magic",
            "identity.clients.restricted-client.required-authentication-method=",
            "identity.clients.restricted-client.allowed-groups[0]=unknown",
            "identity.users.alice.roles[0]=admin",
            "identity.clients.restricted-client.secret=")) {
            contextRunner.withPropertyValues(invalid).run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class PropertiesConfiguration {}
}
