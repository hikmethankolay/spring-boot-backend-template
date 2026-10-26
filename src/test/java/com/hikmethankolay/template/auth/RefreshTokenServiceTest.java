package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.security.TokenRevocationService;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Unit tests of {@link RefreshTokenService} with a mocked repository: rotation, reuse detection, session listing and cleanup. */
@ExtendWith(OutputCaptureExtension.class)
class RefreshTokenServiceTest {

    private static final String RAW = "raw-refresh-token";
    private static final UUID FAMILY = UUID.randomUUID();
    // Recent, so the session is within its absolute lifetime.
    private static final Instant STARTED =
            Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);

    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final TokenRevocationService revocations = mock(TokenRevocationService.class);
    private final RefreshTokenService service =
            new RefreshTokenService(tokens, TestProperties.create(), audit, users, revocations);

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A rotated remember-me token passes its session version, family and long lifetime on to its successor. */
    @Test
    void rotationKeepsTheSessionVersionAndRememberMe() {
        RefreshToken current = stored(true, 3);
        userWithSessionVersion(3);

        RefreshTokenService.Rotation rotation = service.rotate(RAW);

        assertThat(rotation.userId()).isEqualTo(1L);
        assertThat(rotation.successor().rememberMe()).isTrue();
        assertThat(rotation.successor().ttl()).isEqualTo(Duration.ofDays(30));
        assertThat(current.isUsed()).isTrue();
        RefreshToken successor = savedToken();
        assertThat(successor.getSessionVersion()).isEqualTo(3);
        assertThat(successor.isRememberMe()).isTrue();
        assertThat(successor.getFamilyId()).isEqualTo(FAMILY);
        assertThat(successor.getSessionStartedAt()).isEqualTo(STARTED);
        assertThat(rotation.successor().sessionId()).isEqualTo(FAMILY);
    }

    /** A token records the browser and IP of the request that issued it, cut to the column lengths. */
    @Test
    void issuedTokenRecordsTheClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.USER_AGENT, "x".repeat(300));
        request.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        userWithSessionVersion(0);

        RefreshTokenService.IssuedRefreshToken issued = service.issue(1L, 0, false);

        RefreshToken token = savedToken();
        assertThat(token.getUserAgent()).hasSize(RefreshTokenService.MAX_USER_AGENT_LENGTH);
        assertThat(token.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(token.getSessionStartedAt()).isBeforeOrEqualTo(token.getCreatedAt());
        assertThat(issued.sessionId()).isEqualTo(token.getFamilyId());
    }

    /** Outside a request (e.g. in a scheduled job) there is no client to record. */
    @Test
    void tokenIssuedOutsideARequestHasNoClient() {
        userWithSessionVersion(0);

        service.issue(1L, 0, false);

        assertThat(savedToken().getUserAgent()).isNull();
        assertThat(savedToken().getIpAddress()).isNull();
    }

    /** Ending one of the user's sessions deletes its tokens, revokes its access tokens and is audited. */
    @Test
    void revokingASessionEndsItEverywhere() {
        when(tokens.deleteAllByUserIdAndFamilyId(1L, FAMILY)).thenReturn(2);

        service.revoke(1L, FAMILY);

        InOrder order = inOrder(tokens);
        order.verify(tokens).lockAllByUserIdAndFamilyId(1L, FAMILY);
        order.verify(tokens).deleteAllByUserIdAndFamilyId(1L, FAMILY);
        verify(revocations).revokeSession(FAMILY);
        verify(audit).record(AuditEvent.SESSION_REVOKED, 1L, "sessionId", FAMILY);
    }

    /** Another user's session, or one that has ended, is reported as missing and nothing is revoked. */
    @Test
    void revokingAnUnknownSessionIsNotFound() {
        when(tokens.deleteAllByUserIdAndFamilyId(1L, FAMILY)).thenReturn(0);

        assertThatThrownBy(() -> service.revoke(1L, FAMILY))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(revocations, audit);
    }

    /** A rotated normal token keeps the short refresh lifetime. */
    @Test
    void rotationOfANormalSessionKeepsTheShortLifetime() {
        stored(false, 0);
        userWithSessionVersion(0);

        RefreshTokenService.Rotation rotation = service.rotate(RAW);

        assertThat(rotation.successor().rememberMe()).isFalse();
        assertThat(rotation.successor().ttl()).isEqualTo(Duration.ofDays(1));
        assertThat(savedToken().isRememberMe()).isFalse();
    }

    /** Presenting a used token deletes its whole session and records the reuse. */
    @Test
    void reusedTokenEndsTheSessionAndIsAudited() {
        stored(false, 0).markUsed(Instant.now());

        assertThatThrownBy(() -> service.rotate(RAW)).isInstanceOf(BadCredentialsException.class);

        verify(tokens).deleteAllByFamilyId(FAMILY);
        verify(revocations).revokeSession(FAMILY);
        verify(audit).record(AuditEvent.REFRESH_TOKEN_REUSED, 1L, "sessionId", FAMILY);
    }

    /** An expired token is rejected and issues no successor, although its session is still current. */
    @Test
    void expiredTokenIsRejected() {
        when(tokens.findByTokenHash(SecureTokens.hash(RAW)))
                .thenReturn(Optional.of(new RefreshToken(
                        1L,
                        FAMILY,
                        0,
                        SecureTokens.hash(RAW),
                        false,
                        Instant.now().minusSeconds(1),
                        STARTED,
                        "Firefox",
                        "203.0.113.7")));
        userWithSessionVersion(0);

        assertThatThrownBy(() -> service.rotate(RAW)).isInstanceOf(BadCredentialsException.class);
        verify(tokens, never()).save(any());
    }

    /**
     * A session past its absolute lifetime cannot be refreshed, even with an unexpired token from before the cap
     * existed: the session is deleted and the event audited.
     */
    @Test
    void sessionPastItsAbsoluteLifetimeEnds() {
        stored(false, 0, Instant.now().minus(Duration.ofDays(7)).minusSeconds(1));

        assertThatThrownBy(() -> service.rotate(RAW))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(RefreshTokenService.INVALID_REFRESH_TOKEN);

        verify(tokens).deleteAllByFamilyId(FAMILY);
        verify(audit).record(AuditEvent.SESSION_EXPIRED_ABSOLUTE, 1L, "sessionId", FAMILY);
        verify(tokens, never()).save(any());
    }

    /** Remember-me sessions use the longer absolute lifetime. */
    @Test
    void rememberMeSessionOutlivesTheNormalAbsoluteLifetime() {
        stored(true, 0, Instant.now().minus(Duration.ofDays(8)));
        userWithSessionVersion(0);

        assertThat(service.rotate(RAW).successor().rememberMe()).isTrue();
    }

    /** Near the absolute lifetime the successor expires with the session, not a full refresh lifetime later. */
    @Test
    void successorExpiresWithTheSession() {
        Instant started = Instant.now().minus(Duration.ofDays(7)).plus(Duration.ofHours(12));
        stored(false, 0, started);
        userWithSessionVersion(0);

        Duration ttl = service.rotate(RAW).successor().ttl();

        assertThat(ttl).isBetween(Duration.ofHours(11), Duration.ofHours(12));
        assertThat(savedToken().getExpiresAt()).isEqualTo(started.plus(Duration.ofDays(7)));
    }

    /** Ending every session reports how many tokens were deleted. */
    @Test
    void revokeAllReturnsTheNumberOfEndedTokens() {
        when(tokens.deleteAllByUserId(1L)).thenReturn(4);

        assertThat(service.revokeAll(1L)).isEqualTo(4);
    }

    /** A listed session shows its remember-me flag, when it started and was last used, and its client. */
    @Test
    void activeSessionsShowWhenTheirTokenWasIssued() {
        RefreshToken token = new RefreshToken(
                1L, FAMILY, 0, "hash", true, Instant.now().plusSeconds(60), STARTED, "Firefox", "203.0.113.7");
        when(tokens.findActiveByUserId(any(Long.class), any(Instant.class))).thenReturn(List.of(token));

        assertThat(service.activeSessions(1L)).singleElement().satisfies(session -> {
            assertThat(session.rememberMe()).isTrue();
            assertThat(session.startedAt()).isEqualTo(STARTED);
            assertThat(session.lastUsedAt()).isEqualTo(token.getCreatedAt());
            assertThat(session.userAgent()).isEqualTo("Firefox");
            assertThat(session.ipAddress()).isEqualTo("203.0.113.7");
        });
    }

    /** Values within the column length are kept as they are. */
    @Test
    void truncateCutsOnlyLongValues() {
        assertThat(RefreshTokenService.truncate(null, 3)).isNull();
        assertThat(RefreshTokenService.truncate("abc", 3)).isEqualTo("abc");
        assertThat(RefreshTokenService.truncate("abcd", 3)).isEqualTo("abc");
    }

    /**
     * The hourly cleanup logs only when it deleted something.
     *
     * @param output captured console output
     */
    @Test
    void cleanupLogsOnlyWhenTokensWereDeleted(CapturedOutput output) {
        when(tokens.deleteAllExpiredBefore(any(Instant.class))).thenReturn(0);
        service.deleteExpiredTokens();
        assertThat(output).doesNotContain("expired refresh tokens");

        when(tokens.deleteAllExpiredBefore(any(Instant.class))).thenReturn(1);
        service.deleteExpiredTokens();
        assertThat(output).contains("Deleted 1 expired refresh tokens");
    }

    /**
     * Stores a refresh token for {@link #RAW} owned by user 1.
     *
     * @param rememberMe     whether the session uses "remember me"
     * @param sessionVersion the session version the token was issued under
     * @return the stored token
     */
    private RefreshToken stored(boolean rememberMe, int sessionVersion) {
        return stored(rememberMe, sessionVersion, STARTED);
    }

    private RefreshToken stored(boolean rememberMe, int sessionVersion, Instant startedAt) {
        RefreshToken token = new RefreshToken(
                1L,
                FAMILY,
                sessionVersion,
                SecureTokens.hash(RAW),
                rememberMe,
                Instant.now().plusSeconds(60),
                startedAt,
                "Firefox",
                "203.0.113.7");
        when(tokens.findByTokenHash(SecureTokens.hash(RAW))).thenReturn(Optional.of(token));
        return token;
    }

    /**
     * Makes user 1 exist with the given session version.
     *
     * @param version the user's current session version
     */
    private void userWithSessionVersion(int version) {
        User user = mock(User.class);
        when(user.getSessionVersion()).thenReturn(version);
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }

    /**
     * Returns the token passed to the repository's {@code save}.
     *
     * @return the saved token
     */
    private RefreshToken savedToken() {
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(saved.capture());
        return saved.getValue();
    }
}
