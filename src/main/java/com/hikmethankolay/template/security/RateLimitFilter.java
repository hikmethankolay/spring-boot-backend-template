package com.hikmethankolay.template.security;

import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ClientIp;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Limits every client IP to {@code app.rate-limit.requests-per-minute} requests per minute, and to the stricter
 * {@code app.rate-limit.routes} on the paths they name (e.g. 5 sign-ups per hour), each as a
 * sliding window: the previous minute's count is weighted by how much of it still overlaps the last
 * 60 seconds, so a client cannot send twice the limit around the turn of a minute.
 *
 * <p>Counters live in Redis, so the limit holds across all instances. Every response carries the
 * IETF draft headers {@code RateLimit-Limit}, {@code RateLimit-Remaining} and {@code RateLimit-Reset}
 * (seconds until the window resets) of the strictest limit that applies. Over the limit, the request is rejected with a
 * {@code 429} problem detail and {@code Retry-After}. Actuator endpoints are exempt so health probes
 * are never throttled, and requests are allowed if Redis is unavailable.</p>
 *
 * <p>The client IP is {@code request.getRemoteAddr()}; behind a proxy, set
 * {@code server.forward-headers-strategy} so it is the real client address.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Atomically counts the request in the current window (expiring after two windows, so the next
     * window can still read it) and returns the sliding count: the current window plus the previous
     * one weighted by {@code ARGV[2] / ARGV[3]}, the share of it still inside the last minute.
     */
    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            local previous = tonumber(redis.call('GET', KEYS[2]) or '0')
            return current + math.floor(previous * tonumber(ARGV[2]) / tonumber(ARGV[3]))""", Long.class);

    private final StringRedisTemplate redis;

    private final HandlerExceptionResolver resolver;

    private final String keyPrefix;

    private final boolean enabled;

    private final int limit;

    private final List<Route> routes;

    /**
     * Creates the filter.
     *
     * @param redis           Redis access
     * @param resolver        Spring MVC's primary exception resolver
     * @param properties      application properties holding the limit
     * @param applicationName value of {@code spring.application.name}, used to namespace keys
     */
    public RateLimitFilter(
            StringRedisTemplate redis,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            AppProperties properties,
            @Value("${spring.application.name}") String applicationName) {
        this.redis = redis;
        this.resolver = resolver;
        this.keyPrefix = applicationName + ":rate-limit:";
        this.enabled = properties.rateLimit().enabled();
        this.limit = properties.rateLimit().requestsPerMinute();
        this.routes = properties.rateLimit().routes().stream()
                .map(route -> new Route(
                        PathPatternParser.defaultInstance.parse(route.path()), route.requests(), route.window()))
                .toList();
    }

    /**
     * Skips the filter when limiting is disabled and for Actuator endpoints.
     *
     * @param request the current request
     * @return {@code true} if the request is not limited
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || request.getRequestURI().startsWith(request.getContextPath() + "/actuator");
    }

    /**
     * Counts the request and either passes it on or rejects it with 429.
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
        // The braces keep all keys of one client in the same Redis Cluster slot.
        String client = keyPrefix + "{" + ClientIp.limitKey(request.getRemoteAddr()) + "}:";
        PathContainer path = PathContainer.parsePath(
                request.getRequestURI().substring(request.getContextPath().length()));

        Window strictest;
        try {
            strictest = count(client, WINDOW, limit);
            for (int i = 0; i < routes.size(); i++) {
                Route route = routes.get(i);
                if (route.pattern().matches(path)) {
                    strictest = stricter(strictest, count(client + "route" + i + ":", route.window(), route.limit()));
                }
            }
        } catch (DataAccessException ex) {
            log.warn("Rate limit check failed, allowing request: {}", ex.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader("RateLimit-Limit", Integer.toString(strictest.limit()));
        response.setHeader("RateLimit-Remaining", Long.toString(strictest.remaining()));
        response.setHeader("RateLimit-Reset", Long.toString(strictest.resetSeconds()));

        if (strictest.exceeded()) {
            resolver.resolveException(
                    request,
                    response,
                    null,
                    ApiException.tooManyRequests(
                            ProblemType.RATE_LIMITED,
                            "Rate limit exceeded. Try again in " + strictest.resetSeconds() + " seconds.",
                            Duration.ofSeconds(strictest.resetSeconds())));
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Counts the request in one sliding window.
     *
     * @param key    prefix of the window's keys
     * @param window length of the window
     * @param limit  requests allowed in it
     * @return the count, limit and reset time
     */
    private Window count(String key, Duration window, int limit) {
        long windowMillis = window.toMillis();
        long nowMillis = Instant.now().toEpochMilli();
        long index = nowMillis / windowMillis;
        long remainingMillis = windowMillis - nowMillis % windowMillis;
        Long count = redis.execute(
                INCREMENT,
                List.of(key + index, key + (index - 1)),
                Long.toString(2 * windowMillis),
                Long.toString(remainingMillis),
                Long.toString(windowMillis));
        return new Window(count, limit, Math.ceilDiv(remainingMillis, 1000));
    }

    /**
     * Picks the limit to report: an exceeded one (the one that resets last if both are), else the one with the
     * fewest requests left.
     *
     * @param a one window
     * @param b another window
     * @return the stricter of the two
     */
    static Window stricter(Window a, Window b) {
        if (a.exceeded() && b.exceeded()) {
            return a.resetSeconds() >= b.resetSeconds() ? a : b;
        }
        if (a.exceeded() || b.exceeded()) {
            return a.exceeded() ? a : b;
        }
        return a.remaining() <= b.remaining() ? a : b;
    }

    /**
     * A route limit, with its path pattern parsed once.
     *
     * @param pattern the paths it applies to
     * @param limit   requests allowed per window
     * @param window  length of the window
     */
    private record Route(PathPattern pattern, int limit, Duration window) {}

    /**
     * The state of one window after counting a request.
     *
     * @param count        requests in the sliding window, this one included
     * @param limit        requests allowed
     * @param resetSeconds seconds until the window resets
     */
    record Window(long count, int limit, long resetSeconds) {

        /**
         * Tells whether the request is over the limit.
         *
         * @return {@code true} if it must be rejected
         */
        boolean exceeded() {
            return count > limit;
        }

        /**
         * Returns the requests left in the window.
         *
         * @return the remaining requests, never negative
         */
        long remaining() {
            return Math.max(0, limit - count);
        }
    }
}
