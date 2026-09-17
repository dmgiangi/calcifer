package tech.calcifer.auth.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.OAuth2DeviceCode;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2UserCode;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;


class RedisOAuth2AuthorizationStoreTest {

    @Test
    void roundTripsCodesAccessTokensOidcAttributesExpiryAndRemoval() {
        InMemoryRedisByteStore bytes = new InMemoryRedisByteStore();
        RedisOAuth2AuthorizationStore store = new RedisOAuth2AuthorizationStore(bytes, "auth");
        Instant issued = Instant.parse("2026-09-15T12:00:00Z");
        OAuth2AuthorizationCode code = new OAuth2AuthorizationCode("secret-code", issued, issued.plusSeconds(60));
        OAuth2AccessToken access = new OAuth2AccessToken(
            TokenType.BEARER,
            "secret-access",
            issued,
            issued.plusSeconds(300),
            Set.of("openid")
        );
        OAuth2RefreshToken refresh = new OAuth2RefreshToken("secret-refresh", issued, issued.plusSeconds(600));
        OAuth2DeviceCode device = new OAuth2DeviceCode("secret-device", issued, issued.plusSeconds(600));
        OAuth2UserCode user = new OAuth2UserCode("secret-user", issued, issued.plusSeconds(600));
        OAuth2Error error = new OAuth2Error("authorization_pending", "Waiting for user authorization", null);
        OidcIdToken idToken = OidcIdToken
            .withTokenValue("secret-id")
            .issuedAt(issued)
            .expiresAt(issued.plusSeconds(300))
            .subject("user:admin")
            .claim("roles", Set.of("admin"))
            .claim("auth_time", Date.from(issued))
            .build();
        OAuth2Authorization authorization = authorizationBuilder()
            .token(code)
            .accessToken(access)
            .refreshToken(refresh)
            .token(device)
            .token(user)
            .attribute("oauth2.error", error)
            .token(
                idToken,
                metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, Map.of("sub", "user:admin"))
            )
            .attribute("oidc", Map.of("nonce", "present"))
            .build();

        store.save(7, authorization);

        OAuth2Authorization restored = store.findById(7, "authorization-1");
        assertEquals(
            issued.plusSeconds(60),
            restored.getToken(OAuth2AuthorizationCode.class).getToken().getExpiresAt()
        );
        assertEquals(Map.of("nonce", "present"), restored.getAttribute("oidc"));
        assertEquals(Date.from(issued), restored.getToken(OidcIdToken.class).getToken().getClaim("auth_time"));
        assertThat(restored
            .<OAuth2Error>getAttribute("oauth2.error")
            .getErrorCode()).isEqualTo("authorization_pending");
        assertThat(store
            .findByToken(7, "secret-code", new OAuth2TokenType("code"))
            .getId()).isEqualTo(restored.getId());
        assertThat(store
            .findByToken(7, "secret-access", OAuth2TokenType.ACCESS_TOKEN)
            .getId()).isEqualTo(restored.getId());
        assertThat(store
            .findByToken(7, "secret-refresh", OAuth2TokenType.REFRESH_TOKEN)
            .getId()).isEqualTo(restored.getId());
        assertThat(store
            .findByToken(7, "secret-device", new OAuth2TokenType("device_code"))
            .getId()).isEqualTo(restored.getId());
        assertThat(store
            .findByToken(7, "secret-user", new OAuth2TokenType("user_code"))
            .getId()).isEqualTo(restored.getId());
        assertThat(store
            .findByToken(7, "secret-id", new OAuth2TokenType("id_token"))
            .getId()).isEqualTo(restored.getId());
        assertThat(store.findById(6, "authorization-1")).isNull();

        OAuth2Authorization consumed = OAuth2Authorization.from(restored).invalidate(code).build();
        store.save(7, consumed);
        assertThat(store
            .findByToken(7, "secret-code", new OAuth2TokenType("code"))
            .getToken(OAuth2AuthorizationCode.class)
            .isInvalidated()).isTrue();

        store.remove(7, consumed);
        assertThat(store.findById(7, "authorization-1")).isNull();
        assertThat(store.findByToken(7, "secret-code", null)).isNull();
    }

    @Test
    void rejectsUnknownSerializationVersions() {
        VersionedStateSerializer<OAuth2Authorization> serializer
            = new VersionedStateSerializer<>(OAuth2Authorization.class);
        byte[] encoded = serializer.serialize(authorizationBuilder().build());
        encoded[3] = 99;
        assertThatThrownBy(() -> serializer.deserialize(encoded))
            .isInstanceOf(StateUnavailableException.class)
            .hasMessageContaining("Unsupported");
    }

    private static OAuth2Authorization.Builder authorizationBuilder() {
        RegisteredClient client = RegisteredClient
            .withId("registration")
            .clientId("grafana")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://grafana.calcifer.tech/login/generic_oauth")
            .build();
        return OAuth2Authorization
            .withRegisteredClient(client)
            .id("authorization-1")
            .principalName("user:admin")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("openid"));
    }
}
