package com.hikmethankolay.template.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Notices, in the {@code prod} profile, requests whose client address is not public. Behind a proxy that does not pass
 * the client's address on, every request seems to come from the proxy: the rate limit, the login lockout and the audit
 * log then treat all users as one client. Such requests are counted as {@code client_address_not_public_total} and the
 * first one logs a warning; the request itself is served as usual.
 */
@Component
@Profile("prod")
class ClientAddressCheck extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientAddressCheck.class);

    private final Counter notPublic;

    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * Creates the filter.
     *
     * @param meterRegistry registry for the counter
     */
    ClientAddressCheck(MeterRegistry meterRegistry) {
        this.notPublic = Counter.builder("client.address.not.public")
                .description("Requests whose client address is private, loopback or link-local")
                .register(meterRegistry);
    }

    /**
     * Skips Actuator endpoints, which probes and Prometheus call from inside the network.
     *
     * @param request the current request
     * @return {@code true} for Actuator endpoints
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/actuator");
    }

    /**
     * Counts the request if its client address is not public, then passes it on.
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
        String address = request.getRemoteAddr();
        if (!ClientIp.isPublic(address)) {
            notPublic.increment();
            if (warned.compareAndSet(false, true)) {
                log.warn(
                        "A request came from the non-public address {}: if the API is behind a proxy, the rate limit,"
                                + " login lockout and audit log see the proxy's address instead of the client's. Set"
                                + " FORWARD_HEADERS_STRATEGY=native and let the proxy pass the client address (see"
                                + " 'Client IP behind a proxy' in the Production guide). Further requests are only"
                                + " counted in client_address_not_public_total.",
                        // Behind a proxy the address comes from a header; a value that is not an IP may hold one.
                        address == null ? null : address.replaceAll("[\\r\\n]", "_"));
            }
        }
        filterChain.doFilter(request, response);
    }
}
