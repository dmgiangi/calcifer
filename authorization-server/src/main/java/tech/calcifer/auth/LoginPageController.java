package tech.calcifer.auth;

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
  @ResponseBody
  String login(@RequestAttribute(name = "_csrf", required = false) CsrfToken csrfToken) {
    String csrf = csrfToken == null ? "" : "<input type=\"hidden\" name=\"" + csrfToken.getParameterName()
        + "\" value=\"" + csrfToken.getToken() + "\">";
    String passwordForm = properties.localLogin().enabled()
        ? "<form method=\"post\" action=\"/login\">" + csrf
            + "<label>Username <input name=\"username\" autocomplete=\"username\"></label>"
            + "<label>Password <input type=\"password\" name=\"password\" autocomplete=\"current-password\"></label>"
            + "<button type=\"submit\">Accedi con password</button></form>"
        : "";
    return "<!doctype html><html lang=\"it\"><head><meta charset=\"utf-8\"><title>Calcifer login</title></head>"
        + "<body><main><h1>Calcifer login</h1>"
        + "<p><a href=\"/oauth2/authorization/google\">Continua con Google</a></p>"
        + passwordForm + "</main></body></html>";
  }
}
