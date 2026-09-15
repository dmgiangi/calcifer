package tech.calcifer.auth.state;

import java.time.Duration;
import java.time.Instant;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.session.MapSession;

final class AuthorizationStateRuntimeHints implements RuntimeHintsRegistrar {
  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.serialization().registerType(TypeReference.of(MapSession.class));
    hints.serialization().registerType(TypeReference.of(Instant.class));
    hints.serialization().registerType(TypeReference.of(Duration.class));
  }
}