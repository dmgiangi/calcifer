package tech.calcifer.auth.state;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2DeviceCode;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2UserCode;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.session.MapSession;


final class AuthorizationStateRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerSerialization(hints, TypeReference.of(MapSession.class));
        registerSerialization(hints, TypeReference.of(Instant.class));
        registerSerialization(hints, TypeReference.of(Duration.class));
        registerSerialization(hints, TypeReference.of("java.time.Ser"));
        registerSerialization(hints, TypeReference.of(Boolean.class));
        registerSerialization(hints, TypeReference.of(Integer.class));
        registerSerialization(hints, TypeReference.of(Long.class));
        registerSerialization(hints, TypeReference.of(Double.class));
        registerSerialization(hints, TypeReference.of(Date.class));
        registerSerialization(hints, TypeReference.of(URL.class));
        registerSerialization(hints, TypeReference.of(String[].class));
        registerSerialization(hints, TypeReference.of("java.util.CollSer"));
        registerSerialization(hints, TypeReference.of(HashMap.class));
        registerSerialization(hints, TypeReference.of(HashSet.class));
        registerSerialization(hints, TypeReference.of(LinkedHashMap.class));
        registerSerialization(hints, TypeReference.of(LinkedHashSet.class));
        registerSerialization(hints, TypeReference.of(AuthorizationGrantType.class));
        registerSerialization(hints, TypeReference.of(OAuth2Error.class));
        registerSerialization(hints, TypeReference.of(OAuth2AuthorizationRequest.class));
        registerSerialization(hints, TypeReference.of(OAuth2AuthorizationResponseType.class));
        registerSerialization(hints, TypeReference.of(OAuth2AuthenticationToken.class));
        registerSerialization(hints, TypeReference.of(AbstractOAuth2Token.class));
        registerSerialization(hints, TypeReference.of(OAuth2Authorization.class));
        registerSerialization(hints, TypeReference.of(OAuth2Authorization.Token.class));
        registerSerialization(hints, TypeReference.of(OAuth2AuthorizationCode.class));
        registerSerialization(hints, TypeReference.of(OAuth2AccessToken.class));
        registerSerialization(hints, TypeReference.of(OAuth2AccessToken.TokenType.class));
        registerSerialization(hints, TypeReference.of(OAuth2RefreshToken.class));
        registerSerialization(hints, TypeReference.of(OAuth2DeviceCode.class));
        registerSerialization(hints, TypeReference.of(OAuth2UserCode.class));
        registerSerialization(hints, TypeReference.of(OidcIdToken.class));
        registerSerialization(hints, TypeReference.of(OidcUserInfo.class));
        registerSerialization(hints, TypeReference.of(DefaultOAuth2User.class));
        registerSerialization(hints, TypeReference.of(DefaultOidcUser.class));
        registerSerialization(hints, TypeReference.of(Collections.unmodifiableMap(new HashMap<>()).getClass()));
        registerSerialization(hints, TypeReference.of(Collections.unmodifiableSet(new HashSet<>()).getClass()));
        registerSerialization(hints, TypeReference.of(List.of().getClass()));
        registerSerialization(hints, TypeReference.of(List.of("value").getClass()));
        registerSerialization(hints, TypeReference.of(Map.of().getClass()));
        registerSerialization(hints, TypeReference.of(Map.of("key", "value").getClass()));
        registerSerialization(hints, TypeReference.of(Set.of().getClass()));
        registerSerialization(hints, TypeReference.of(Set.of("value").getClass()));
    }

    private static void registerSerialization(RuntimeHints hints, TypeReference type) {
        hints.reflection().registerType(type, typeHint -> typeHint.withJavaSerialization(true));
    }
}
