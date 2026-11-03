package com.hikmethankolay.template.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** The paths of IdempotencyFilter an integration test cannot reach: Redis failures, server errors, anonymous calls. */
class IdempotencyFilterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);

    private final IdempotencyFilter filter = new IdempotencyFilter(redis, resolver, "app");

    @BeforeEach
    void signIn() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("7", null, "ROLE_USER"));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlySignedInPostsWithAKeyAreCovered() {
        MockHttpServletRequest get = request();
        get.setMethod("GET");
        MockHttpServletRequest noKey = request();
        noKey.removeHeader(IdempotencyFilter.HEADER);
        assertThat(filter.shouldNotFilter(get)).isTrue();
        assertThat(filter.shouldNotFilter(noKey)).isTrue();
        assertThat(filter.shouldNotFilter(request())).isFalse();

        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(filter.shouldNotFilter(request())).isTrue();
        SecurityContextHolder.clearContext();
        assertThat(filter.shouldNotFilter(request())).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    void responsesWithSecretsAreNeverKept() {
        // Two-factor enrollment answers with the TOTP secret and the recovery codes: never copy them to Redis.
        MockHttpServletRequest enroll = request();
        enroll.setRequestURI("/api/v1/users/me/mfa/totp");
        MockHttpServletRequest confirm = request();
        confirm.setRequestURI("/api/v1/users/me/mfa/totp/confirm");

        assertThat(filter.shouldNotFilter(enroll)).isTrue();
        assertThat(filter.shouldNotFilter(confirm)).isTrue();
        verifyNoInteractions(redis);
        // New access tokens and webhook signing secrets are shown once and must not sit in Redis either.
        assertThat(IdempotencyFilter.covers("/api/v1/users/me/access-tokens")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/webhooks")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/webhooks/12/secret")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/webhooks/{id}/secret")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/webhooks/12/test")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/notes")).isTrue();
    }

    @Test
    void aRequestReleasesOnlyItsOwnClaim() throws Exception {
        // The claim expires while a slow request runs; a retry may then hold it, and must keep it.
        ArgumentCaptor<String> claim = ArgumentCaptor.forClass(String.class);
        when(values.setIfAbsent(anyString(), claim.capture(), any(Duration.class)))
                .thenReturn(true);

        filter.doFilter(
                request(),
                new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value()));

        verify(redis).execute(eq(IdempotencyFilter.RELEASE), anyList(), eq(claim.getValue()));
        verify(redis, never()).delete(anyString());
        filter.doFilter(
                request(),
                new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value()));
        assertThat(claim.getAllValues().get(1))
                .isNotEqualTo(claim.getAllValues().get(0));
    }

    @Test
    void aLargeBodyRunsWithoutAKeyInsteadOfBeingBuffered() throws Exception {
        byte[] large = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(large, (byte) 'a');
        MockHttpServletRequest request = request();
        request.setContent(large);
        AtomicReference<Integer> read = new AtomicReference<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> read.set(req.getInputStream().readAllBytes().length));

        assertThat(read.get()).isEqualTo(large.length);
        verifyNoInteractions(redis);
    }

    @Test
    void aBlankKeyIsRefused() throws Exception {
        MockHttpServletRequest request = request();
        request.removeHeader(IdempotencyFilter.HEADER);
        request.addHeader(IdempotencyFilter.HEADER, "  ");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(resolver)
                .resolveException(
                        any(),
                        any(),
                        any(),
                        argThat(ex -> ex instanceof ApiException api
                                && api.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()));
        verifyNoInteractions(chain);
    }

    @Test
    void withoutRedisTheRequestRunsWithItsBodyIntact() throws Exception {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        AtomicReference<String> body = new AtomicReference<>();

        filter.doFilter(
                request(),
                new MockHttpServletResponse(),
                (req, res) -> body.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(body.get()).isEqualTo("{\"title\":\"a\"}");
    }

    @Test
    void aServerErrorIsNotKeptSoTheRequestCanBeRetried() throws Exception {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilter(
                request(),
                new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value()));

        verify(redis, never()).execute(eq(IdempotencyFilter.STORE), anyList(), any(Object[].class));
        verify(redis).execute(eq(IdempotencyFilter.RELEASE), anyList(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 409, 429})
    void aTransientClientErrorIsNotKeptSoTheRetryRunsAgain(int status) throws Exception {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilter(
                request(), new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(status));

        verify(redis, never()).execute(eq(IdempotencyFilter.STORE), anyList(), any(Object[].class));
        verify(redis).execute(eq(IdempotencyFilter.RELEASE), anyList(), any(Object[].class));
    }

    @Test
    void finalAnswersAreKeptAndTransientOnesAreNot() {
        assertThat(IdempotencyFilter.keeps(200)).isTrue();
        assertThat(IdempotencyFilter.keeps(201)).isTrue();
        assertThat(IdempotencyFilter.keeps(400)).isTrue();
        assertThat(IdempotencyFilter.keeps(404)).isTrue();
        assertThat(IdempotencyFilter.keeps(422)).isTrue();
        assertThat(IdempotencyFilter.keeps(499)).isTrue();
        assertThat(IdempotencyFilter.keeps(408)).isFalse();
        assertThat(IdempotencyFilter.keeps(409)).isFalse();
        assertThat(IdempotencyFilter.keeps(429)).isFalse();
        assertThat(IdempotencyFilter.keeps(500)).isFalse();
        assertThat(IdempotencyFilter.keeps(503)).isFalse();
    }

    @Test
    void aResponseThatCannotBeKeptReleasesTheKey() throws Exception {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(eq(IdempotencyFilter.STORE), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        filter.doFilter(
                request(),
                new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(HttpStatus.NO_CONTENT.value()));

        verify(redis).execute(eq(IdempotencyFilter.RELEASE), anyList(), any(Object[].class));
    }

    @Test
    void aResponseWithoutBodyTypeOrLocationIsReplayedAsItWas() throws Exception {
        // First request: kept without Content-Type or Location.
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        AtomicReference<Object> fingerprint = new AtomicReference<>();
        Map<Object, Object> kept = new HashMap<>();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    // ARGV: TTL, fingerprint, then the hash's field/value pairs.
                    Object[] args = (Object[]) invocation.getRawArguments()[2];
                    fingerprint.set(args[1]);
                    for (int i = 2; i < args.length; i += 2) {
                        kept.put(args[i], args[i + 1]);
                    }
                    return 1L;
                });
        filter.doFilter(
                request(),
                new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(HttpStatus.NO_CONTENT.value()));
        assertThat(kept).containsOnlyKeys("status", "body");

        // The retry gets it back.
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(anyString())).thenReturn((String) fingerprint.get());
        when(hashes.entries(anyString())).thenReturn(kept);
        MockHttpServletResponse replay = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request(), replay, chain);

        assertThat(replay.getStatus()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(replay.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(replay.getContentType()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void aRetryThatCannotBeCheckedIsAskedToRetryAgain() throws Exception {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        verify(resolver)
                .resolveException(
                        any(),
                        any(),
                        any(),
                        argThat(ex -> ex instanceof ApiException api
                                && api.getStatusCode().value() == HttpStatus.CONFLICT.value()));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/notes");
        request.addHeader(IdempotencyFilter.HEADER, "key-1");
        request.setContent("{\"title\":\"a\"}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }
}
