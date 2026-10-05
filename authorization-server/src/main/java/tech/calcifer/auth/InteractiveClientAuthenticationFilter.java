package tech.calcifer.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

/** Starts Google step-up without discarding the original OAuth state, nonce or PKCE challenge. */
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
        if ("GET".equals(request.getMethod())
            && (request.getContextPath() + "/oauth2/authorize").equals(request.getRequestURI())
            && policy.requiresGoogleAuthentication(request.getParameter("client_id"),
                SecurityContextHolder.getContext().getAuthentication())) {
            if ("none".equals(request.getParameter("prompt"))) {
                // A silent request must never start interactive authentication or issue a code.
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            requestCache.saveRequest(request, response);
            response.sendRedirect(request.getContextPath() + "/oauth2/authorization/google");
            return;
        }
        chain.doFilter(request, response);
    }
}
