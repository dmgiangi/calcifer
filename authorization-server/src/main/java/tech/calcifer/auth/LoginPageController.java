package tech.calcifer.auth;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
class LoginPageController {
  private final IdentityProperties properties;

  LoginPageController(IdentityProperties properties) {
    this.properties = properties;
  }

  @GetMapping(value = "/login", produces = "text/html")
  String login() {
    return "forward:/login.html";
  }

  @GetMapping(value = "/login/config", produces = "application/json")
  @ResponseBody
  Map<String, Boolean> loginConfig() {
    return Map.of("passwordEnabled", properties.localLogin().enabled());
  }

  @GetMapping(value = "/login/csrf", produces = "application/json")
  @ResponseBody
  Map<String, String> csrf(@RequestAttribute(name = "_csrf") CsrfToken csrfToken) {
    return Map.of("parameterName", csrfToken.getParameterName(), "headerName", csrfToken.getHeaderName(),
        "token", csrfToken.getToken());
  }
}
