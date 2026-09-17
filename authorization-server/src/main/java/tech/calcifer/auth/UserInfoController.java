package tech.calcifer.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
class UserInfoController {

    @GetMapping("/userinfo")
    Map<String, Object> userInfo(JwtAuthenticationToken authentication) {
        var token = authentication.getToken();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", token.getSubject());
        if (token.hasClaim("email")) {
            claims.put("email", token.getClaimAsString("email"));
        }
        if (token.hasClaim("roles")) {
            claims.put("roles", token.getClaimAsStringList("roles"));
        }
        return claims;
    }
}
