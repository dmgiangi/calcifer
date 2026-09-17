package tech.calcifer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;


class LoginSuccessHandlerTest {

    private final AuthorizationServerConfiguration configuration = new AuthorizationServerConfiguration();
    private final TestingAuthenticationToken authentication = new TestingAuthenticationToken(
        "user:admin",
        "ignored",
        "ROLE_ADMIN"
    );

    @Test
    void directLoginUsesTheCanonicalRootAsItsSafeDefaultDestination() throws Exception {
        RequestCache cache = configuration.authorizationRequestCache();
        AuthenticationSuccessHandler handler = configuration.oauth2LoginSuccessHandler(cache);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest("POST", "/login"), response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void loginResumesSavedAuthorizationRequestSoTheSessionProvidesSso() throws Exception {
        RequestCache cache = configuration.authorizationRequestCache();
        MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
        authorize.setQueryString("client_id=grafana&response_type=code");
        cache.saveRequest(authorize, new MockHttpServletResponse());
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/login");
        login.setSession(authorize.getSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        configuration.oauth2LoginSuccessHandler(cache).onAuthenticationSuccess(login, response, authentication);

        assertThat(response.getRedirectedUrl()).contains("/oauth2/authorize?client_id=grafana&response_type=code");
    }
}
