package tech.calcifer.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shows central login or starts Google step-up, preserving OAuth state, nonce and PKCE. */
final class InteractiveClientAuthenticationFilter extends OncePerRequestFilter {

    private final InteractiveClientGroupPolicy policy;
    private final RequestCache requestCache;

    InteractiveClientAuthenticationFilter(IdentityProperties properties, RequestCache requestCache) {
        this.policy = new InteractiveClientGroupPolicy(properties);
        this.requestCache = requestCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if ("GET".equals(request.getMethod())
            && (request.getContextPath() + "/oauth2/authorize").equals(request.getRequestURI())
            && policy.requiresGoogleAuthentication(request.getParameter("client_id"), authentication)) {
            if ("none".equals(request.getParameter("prompt"))) {
                // A silent request must never start interactive authentication or issue a code.
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            requestCache.saveRequest(request, response);
            boolean anonymous = authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
            response.sendRedirect(request.getContextPath()
                + (anonymous ? "/login" : "/oauth2/authorization/google"));
            return;
        }
        chain.doFilter(request, response);
    }
}
