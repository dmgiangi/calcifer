package tech.calcifer.auth;

import java.util.Collection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ForwardAuthController {
  private final JwtDecoder jwtDecoder;
  private final IdentityProperties properties;

  ForwardAuthController(JwtDecoder jwtDecoder, IdentityProperties properties) {
    this.jwtDecoder = jwtDecoder;
    this.properties = properties;
  }

  @GetMapping("/internal/traefik/forward-auth")
  ResponseEntity<Void> authenticate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    if (authorization == null || authorization.isBlank()) return ResponseEntity.noContent().build();
    if (!authorization.startsWith("Bearer ")) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    try {
      Jwt token = jwtDecoder.decode(authorization.substring("Bearer ".length()));
      Collection<String> audience = token.getAudience();
      String scope = token.getClaimAsString("scope");
      if (!properties.issuer().equals(token.getIssuer().toString()) || !audience.contains("grafana")
          || scope == null || !scope.contains("grafana.api") || !properties.grafanaApi().id().equals(token.getSubject())) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
      }
      return ResponseEntity.noContent()
          .header("X-WEBAUTH-USER", "service:" + token.getSubject())
          .header("X-WEBAUTH-ROLE", "Admin")
          .build();
    } catch (RuntimeException exception) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
  }
}
