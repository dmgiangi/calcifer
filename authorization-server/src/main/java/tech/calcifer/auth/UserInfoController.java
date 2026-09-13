package tech.calcifer.auth;

import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserInfoController {
  @GetMapping("/userinfo")
  Map<String, Object> userInfo(JwtAuthenticationToken authentication) {
    var token = authentication.getToken();
    return Map.of(
        "sub", token.getSubject(),
        "email", token.getClaimAsString("email"),
        "roles", token.getClaimAsStringList("roles"));
  }
}
