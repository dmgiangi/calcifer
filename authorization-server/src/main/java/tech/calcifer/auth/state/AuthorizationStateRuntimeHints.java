package tech.calcifer.auth.state;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.session.MapSession;

final class AuthorizationStateRuntimeHints implements RuntimeHintsRegistrar {
  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.serialization().registerType(TypeReference.of(MapSession.class));
    hints.serialization().registerType(TypeReference.of(Instant.class));
    hints.serialization().registerType(TypeReference.of(Duration.class));
    hints.serialization().registerType(TypeReference.of("java.time.Ser"));
    hints.serialization().registerType(TypeReference.of(HashMap.class));
    hints.serialization().registerType(TypeReference.of(HashSet.class));
    hints.serialization().registerType(TypeReference.of(LinkedHashMap.class));
    hints.serialization().registerType(TypeReference.of(LinkedHashSet.class));
    hints.serialization().registerType(TypeReference.of(AuthorizationGrantType.class));
    hints.serialization().registerType(TypeReference.of(OAuth2AuthorizationRequest.class));
    hints.serialization().registerType(TypeReference.of(OAuth2AuthorizationResponseType.class));
    hints.serialization().registerType(TypeReference.of(Collections.unmodifiableMap(new HashMap<>()).getClass()));
    hints.serialization().registerType(TypeReference.of(Collections.unmodifiableSet(new HashSet<>()).getClass()));
  }
}