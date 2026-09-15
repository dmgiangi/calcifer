package tech.calcifer.auth.state;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("identity.state")
public record AuthorizationStateProperties(
    boolean enabled,
    @NotNull Role role,
    @NotNull @Valid Redis redis,
    @Min(1) int failureThreshold,
    @NotNull Duration probeInterval,
    @NotNull Duration minimumIsolation,
    @NotNull Duration stableSuccess,
    @NotNull Duration leaseTtl,
    @NotNull Duration gateTtl,
    @NotNull Duration drain,
    @Min(1) @Max(100) int cleanupBatchSize,
    @NotNull Duration accessTokenTtl) {

  public enum Role { CLOUD, HOME }

  public record Redis(String host, @Min(1) @Max(65535) int port, String username,
      String password, @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+") String namespace) {}

  @AssertTrue(message = "enabled resilient state requires Redis host, username, and password")
  public boolean isEnabledRedisConfigurationValid() {
    return !enabled || (redis != null && hasText(redis.host())
        && hasText(redis.username()) && hasText(redis.password()));
  }

  @AssertTrue(message = "recovery timing values must be positive and the gate must outlive the drain")
  public boolean isRecoveryTimingValid() {
    return positive(probeInterval) && positive(minimumIsolation) && positive(stableSuccess)
        && positive(leaseTtl) && positive(gateTtl) && drain != null && !drain.isNegative()
        && gateTtl.compareTo(drain) > 0 && leaseTtl.compareTo(drain) > 0
        && positive(accessTokenTtl);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean positive(Duration value) {
    return value != null && !value.isZero() && !value.isNegative();
  }
}
