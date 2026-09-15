package tech.calcifer.auth.state;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;


final class StateRouteFilter extends OncePerRequestFilter {

    static final String ROUTE_ATTRIBUTE = StateRoute.class.getName();
    private final AuthorizationStateManager state;

    StateRouteFilter(AuthorizationStateManager state) {
        this.state = state;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        boolean stateful = isStateful(request.getMethod(), request.getRequestURI());
        StateRoute route = stateful ? state.routeForStatefulRequest() : StateRoute.unavailable();
        request.setAttribute(ROUTE_ATTRIBUTE, route);
        RequestStateContext.bind(route);
        try {
            if (stateful && route.owner() == StateRoute.Owner.UNAVAILABLE) {
                unavailable(response);
                return;
            }
            if (route.owner() == StateRoute.Owner.LOCAL && isGoogleFlow(request.getRequestURI())) {
                unavailable(response);
                return;
            }
            chain.doFilter(request, response);
        } catch (StateUnavailableException exception) {
            if (response.isCommitted()) {
                throw exception;
            }
            unavailable(response);
        } finally {
            RequestStateContext.clear();
        }
    }

    static boolean isStateful(String method, String path) {
        if (path.startsWith("/.well-known/")
            || path.equals("/oauth2/jwks")
            || path.startsWith("/actuator/")
            || path.equals("/login.html")
            || path.equals("/login.css")
            || path.equals("/login.js")
            || path.equals("/google-mark.svg")
            || path.equals("/login/config")
            || path.equals("/error")
            || path.equals("/internal/traefik/forward-auth")) {
            return false;
        }
        // Spring Session runs before the security chains. Every non-static request
        // that can read or create an HTTP session therefore needs one immutable
        // route, not only the OAuth protocol endpoints.
        return true;
    }

    private static boolean isGoogleFlow(String path) {
        return path.equals("/oauth2/authorization/google") || path.equals("/login/oauth2/code/google");
    }

    private static void unavailable(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", "2");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"temporarily_unavailable\"}");
    }
}
