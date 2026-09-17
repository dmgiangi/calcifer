package tech.calcifer.auth;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;


@RestController
class ForwardAuthController {

    private static final Logger log = LoggerFactory.getLogger(ForwardAuthController.class);
    private final JwtDecoder jwtDecoder;
    private final IdentityProperties properties;

    ForwardAuthController(JwtDecoder jwtDecoder, IdentityProperties properties) {
        this.jwtDecoder = jwtDecoder;
        this.properties = properties;
    }

    @GetMapping("/internal/traefik/forward-auth")
    ResponseEntity<Void> authenticate(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization == null || authorization.isBlank()) {
            log.info("forward_auth_outcome=browser_session");
            return ResponseEntity.noContent().build();
        }
        if (!authorization.startsWith("Bearer ")) {
            log.info("forward_auth_outcome=invalid_scheme");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Jwt token = jwtDecoder.decode(authorization.substring("Bearer ".length()));
            if (token.getExpiresAt() == null || !Instant.now().isBefore(token.getExpiresAt())) {
                log.info("forward_auth_outcome=expired_token");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Collection<String> audience = token.getAudience();
            Object scopeClaim = token.getClaims().get("scope");
            Set<String> scopes;
            if (scopeClaim instanceof String scope) {
                scopes = Set.copyOf(Arrays.asList(scope.split("\\s+")));
            } else if (scopeClaim instanceof Collection<?> scopeValues) {
                scopes = scopeValues
                    .stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            } else {
                scopes = Set.of();
            }
            if (!properties.issuer().equals(token.getIssuer().toString()) || audience == null || !audience.contains(
                properties.grafanaApi().audience()) || !scopes.contains("grafana.api") || !properties
                .grafanaApi()
                .id()
                .equals(token.getSubject())) {
                log.info("forward_auth_outcome=forbidden");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            log.info("forward_auth_outcome=machine_token");
            return ResponseEntity
                .noContent()
                .header("X-WEBAUTH-USER", "service:" + token.getSubject())
                .header("X-WEBAUTH-ROLE", "Admin")
                .build();
        } catch (RuntimeException exception) {
            log.info("forward_auth_outcome=invalid_token reason={}", exception.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
