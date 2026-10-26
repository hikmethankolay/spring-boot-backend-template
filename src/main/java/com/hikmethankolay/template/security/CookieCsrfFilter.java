package com.hikmethankolay.template.security;

import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.WebUtils;

/**
 * Protects cookie-authenticated requests against cross-site request forgery. {@code SameSite=Strict} keeps the
 * cookies off requests started by other sites, but sibling subdomains count as the same site, so it is only defence
 * in depth (OWASP CSRF Prevention).
 *
 * <p>A state-changing request (not {@code GET}, {@code HEAD}, {@code OPTIONS} or {@code TRACE}) that carries an
 * auth cookie and no {@code Authorization} header must come from an allowed origin: its {@code Origin} is in
 * {@code app.cors.allowed-origins} or is the API's own, or it carries {@code X-Requested-With: XMLHttpRequest}, a
 * header other sites cannot send without passing CORS. Anything else is answered {@code 403}. Requests with a
 * Bearer header are not affected: a browser never adds that header on its own.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class CookieCsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final HandlerExceptionResolver resolver;

    private final List<String> allowedOrigins;

    private final List<String> cookieNames;

    /**
     * Creates the filter.
     *
     * @param resolver   Spring MVC's primary exception resolver
     * @param properties allowed origins and cookie names
     */
    public CookieCsrfFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver, AppProperties properties) {
        this.resolver = resolver;
        List<String> origins = properties.cors().allowedOrigins();
        this.allowedOrigins = origins == null ? List.of() : List.copyOf(origins);
        this.cookieNames = List.of(
                properties.cookie().accessCookieName(), properties.cookie().refreshCookieName());
    }

    /**
     * Skips safe methods and requests that are not authenticated by cookie.
     *
     * @param request the current request
     * @return {@code true} if the request cannot be a forged state change
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod())
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || cookieNames.stream().noneMatch(name -> WebUtils.getCookie(request, name) != null);
    }

    /**
     * Passes requests from allowed origins on and rejects the rest with 403.
     *
     * @param request     the current request
     * @param response    the current response
     * @param filterChain the remaining filters
     * @throws ServletException if a later filter fails
     * @throws IOException      if writing the response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        resolver.resolveException(
                request,
                response,
                null,
                new ApiException(
                        HttpStatus.FORBIDDEN,
                        ProblemType.CROSS_SITE_REQUEST,
                        "Send X-Requested-With: XMLHttpRequest, or call from an allowed origin."));
    }

    /**
     * Tells whether a cookie-authenticated request comes from a page allowed to change state.
     *
     * @param request the request
     * @return {@code true} for an allowed or own {@code Origin}, or {@code X-Requested-With: XMLHttpRequest}
     */
    private boolean isAllowed(HttpServletRequest request) {
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return true;
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        return origin != null && (allowedOrigins.contains(origin) || origin.equals(ownOrigin(request)));
    }

    /**
     * Builds the origin the API itself is served from, as a browser would send it.
     *
     * @param request the request
     * @return scheme, host and, unless it is the scheme's default, port
     */
    private static String ownOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }
}
