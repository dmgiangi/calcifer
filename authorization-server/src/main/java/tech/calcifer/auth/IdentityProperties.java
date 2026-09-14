package tech.calcifer.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("identity")
public record IdentityProperties(
    @NotBlank String issuer,
    @NotBlank String allowedGoogleEmail,
    @NotBlank String canonicalUserId,
    @NotBlank String signingKeyLocation,
    @Valid Client grafana,
    @Valid Client grafanaApi,
    @Valid LocalLogin localLogin) {
  public record Client(@NotBlank String id, @NotBlank String secret, @NotBlank String redirectUri,
      @NotBlank String audience) {}
  public record LocalLogin(boolean enabled, @NotBlank String username, String passwordHash) {}
}
