package com.hikmethankolay.template.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes retried {@code POST} requests safe with the {@code Idempotency-Key} header (IETF
 * draft-ietf-httpapi-idempotency-key-header). When a signed-in client sends a {@code POST} with a key, every later
 * {@code POST} of that client with the same key, for {@value #TTL_HOURS} hours, gets the first response again
 * (marked {@code Idempotent-Replayed: true}) instead of running a second time: a request retried after a timeout does
 * not create a second note or queue a second broadcast.
 *
 * <ul>
 *   <li>Keys belong to the caller; two users can use the same key.</li>
 *   <li>The same key with another path or body is refused with {@code 422}; a retry while the first request still
 *       runs gets {@code 409}.</li>
 *   <li>A {@code 5xx} response is not kept, so the request can be retried.</li>
 *   <li>Anonymous requests (login, sign-up) are not covered: a replayed login would give its tokens to anyone who
 *       knows the key.</li>
 *   <li>Without Redis the header is ignored and requests run as usual (ADR 2).</li>
 * </ul>
 *
 * <p>The filter runs after Spring Security, so it knows the caller.</p>
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    /** Request header carrying the client's key. */
    public static final String HEADER = "Idempotency-Key";

    /** Response header set on replayed responses. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private static final int TTL_HOURS = 24;

    private static final Duration TTL = Duration.ofHours(TTL_HOURS);

    /** How long a key stays claimed by a request that has not answered, e.g. because its instance stopped. */
    private static final Duration IN_PROGRESS_TTL = Duration.ofMinutes(1);

    private static final int MAX_KEY_LENGTH = 255;

    private final StringRedisTemplate redis;

    private final HandlerExceptionResolver resolver;

    private final String keyPrefix;

    /**
     * Creates the filter.
     *
     * @param redis           stores the keys and responses
     * @param resolver        Spring MVC's primary exception resolver
     * @param applicationName value of {@code spring.application.name}, used to namespace keys
     */
    public IdempotencyFilter(
            StringRedisTemplate redis,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            @Value("${spring.application.name}") String applicationName) {
        this.redis = redis;
        this.resolver = resolver;
        this.keyPrefix = applicationName + ":idempotency:";
    }

    /**
     * Skips everything but signed-in {@code POST} requests with the header.
     *
     * @param request the current request
     * @return {@code true} if the request is not covered
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null || caller() == null;
    }

    /**
     * Runs the request once per key and replays its response to retries.
     *
     * @param request  the current request
     * @param response the current response
     * @param chain    the remaining filters
     * @throws ServletException if a later filter fails
     * @throws IOException      if reading the request or writing the response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            reject(
                    request,
                    response,
                    HttpStatus.BAD_REQUEST,
                    ProblemType.IDEMPOTENCY_KEY_INVALID,
                    "The Idempotency-Key header must have 1 to " + MAX_KEY_LENGTH + " characters.");
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        String fingerprint = fingerprint(request, cached.body);
        // The braces keep both keys of one caller in the same Redis Cluster slot.
        String claimKey = keyPrefix + "{" + caller() + "}:" + SecureTokens.hash(key);
        String responseKey = claimKey + ":response";

        Boolean claimed;
        try {
            claimed = redis.opsForValue().setIfAbsent(claimKey, fingerprint, IN_PROGRESS_TTL);
        } catch (DataAccessException ex) {
            log.warn("Idempotency keys unavailable, running the request without one: {}", ex.getMessage());
            chain.doFilter(cached, response);
            return;
        }
        if (!Boolean.TRUE.equals(claimed)) {
            replayOrReject(cached, response, claimKey, responseKey, fingerprint);
            return;
        }

        ContentCachingResponseWrapper recorded = new ContentCachingResponseWrapper(response);
        boolean kept = false;
        try {
            chain.doFilter(cached, recorded);
            if (recorded.getStatus() < 500) {
                kept = store(recorded, claimKey, responseKey, fingerprint);
            }
        } finally {
            if (!kept) {
                release(claimKey);
            }
            recorded.copyBodyToResponse();
        }
    }

    private void replayOrReject(
            HttpServletRequest request,
            HttpServletResponse response,
            String claimKey,
            String responseKey,
            String fingerprint)
            throws IOException {
        String claimedFor;
        Map<Object, Object> saved;
        try {
            claimedFor = redis.opsForValue().get(claimKey);
            saved = redis.opsForHash().entries(responseKey);
        } catch (DataAccessException ex) {
            log.warn("Idempotency keys unavailable: {}", ex.getMessage());
            reject(
                    request,
                    response,
                    HttpStatus.CONFLICT,
                    ProblemType.IDEMPOTENCY_KEY_IN_USE,
                    "The request with this Idempotency-Key cannot be checked now; retry it.");
            return;
        }
        if (saved.isEmpty()) {
            reject(
                    request,
                    response,
                    HttpStatus.CONFLICT,
                    ProblemType.IDEMPOTENCY_KEY_IN_USE,
                    "A request with this Idempotency-Key is still running; retry it later.");
            return;
        }
        if (!fingerprint.equals(claimedFor)) {
            reject(
                    request,
                    response,
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ProblemType.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was used for a different request.");
            return;
        }
        response.setStatus(Integer.parseInt((String) saved.get("status")));
        if (saved.get("contentType") instanceof String contentType) {
            response.setContentType(contentType);
        }
        if (saved.get("location") instanceof String location) {
            response.setHeader(HttpHeaders.LOCATION, location);
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.getOutputStream().write(Base64.getDecoder().decode((String) saved.get("body")));
    }

    private boolean store(
            ContentCachingResponseWrapper recorded, String claimKey, String responseKey, String fingerprint) {
        Map<String, String> fields = new HashMap<>();
        fields.put("status", Integer.toString(recorded.getStatus()));
        fields.put("body", Base64.getEncoder().encodeToString(recorded.getContentAsByteArray()));
        if (recorded.getContentType() != null) {
            fields.put("contentType", recorded.getContentType());
        }
        if (recorded.getHeader(HttpHeaders.LOCATION) != null) {
            fields.put("location", recorded.getHeader(HttpHeaders.LOCATION));
        }
        try {
            redis.opsForHash().putAll(responseKey, fields);
            redis.expire(responseKey, TTL);
            redis.opsForValue().set(claimKey, fingerprint, TTL);
            return true;
        } catch (DataAccessException ex) {
            log.warn("Could not keep the response for its Idempotency-Key: {}", ex.getMessage());
            return false;
        }
    }

    private void release(String claimKey) {
        try {
            redis.delete(claimKey);
        } catch (DataAccessException ex) {
            log.warn("Could not release an Idempotency-Key: {}", ex.getMessage());
        }
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            ProblemType type,
            String detail) {
        resolver.resolveException(request, response, null, new ApiException(status, type, detail));
    }

    /**
     * Returns the signed-in caller.
     *
     * @return the principal's name (the user id), or {@code null} for anonymous requests
     */
    private static String caller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null
                        || !authentication.isAuthenticated()
                        || authentication instanceof AnonymousAuthenticationToken
                ? null
                : authentication.getName();
    }

    /**
     * Identifies a request by its path, query and body, so a key cannot be reused for another request.
     *
     * @param request the request
     * @param body    its body
     * @return hex SHA-256
     */
    private static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(
                    (request.getRequestURI() + "?" + request.getQueryString() + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** A request whose body was read for the fingerprint and can be read again by the controller. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Asynchronous reads are not supported");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
