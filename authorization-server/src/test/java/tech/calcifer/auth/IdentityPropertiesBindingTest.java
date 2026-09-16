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
            "identity.allowed-google-email=admin@example.com",
            "identity.canonical-user-id=user:admin",
            "identity.signing-key-location=file:key",
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
            "identity.clients.homepage.id=homepage",
            "identity.clients.homepage.secret=${HOMEPAGE_OIDC_CLIENT_SECRET}",
            "identity.clients.homepage.redirect-uris[0]=https://calcifer.tech/api/auth/callback/homepage-oidc",
            "identity.clients.homepage.scopes[0]=openid",
            "identity.clients.homepage.scopes[1]=email",
            "identity.clients.homepage.grant-types[0]=authorization_code",
            "identity.clients.homepage.authentication-methods[0]=client_secret_post",
            "identity.clients.homepage.audience=homepage",
            "identity.clients.homepage.require-proof-key=true",
            "identity.clients.homepage.access-token-ttl=2m"
        );

    @Test
    void resolvesSecretPlaceholderAndBindsDeclarativeClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var homepage = context.getBean(IdentityProperties.class).clients().get("homepage");
            assertThat(homepage.secret()).isEqualTo("resolved-secret");
            assertThat(homepage.accessTokenTtl()).hasMinutes(2);
            assertThat(homepage.requireProofKey()).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class PropertiesConfiguration {}
}
