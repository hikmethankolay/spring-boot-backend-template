package com.hikmethankolay.template.auth;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.security.TokenRevocationService;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>A refresh token is an opaque 256-bit random value; only its SHA-256 hash is stored. Each one
 * can be exchanged exactly once: the exchange marks it used and issues a successor in the same
 * family. Presenting a used token again means it was copied, so the whole family (the session) is
 * revoked with its access tokens and the event is audited; whoever holds the newest token must log in again.</p>
 *
 * <p>Each token records when its session started and the {@code User-Agent} and IP of the request that issued it,
 * so users can recognise their sessions in {@code GET /api/v1/users/me/sessions} and end the ones they don't.</p>
 *
 * <p>Expiry is sliding: each exchange starts a new {@code app.jwt.refresh-token-ttl} (or
 * {@code app.jwt.remember-me-ttl}) period, so a session ends after that much inactivity. It is also absolute: a
 * session ends {@code app.jwt.max-session-ttl} (or {@code app.jwt.remember-me-max-session-ttl}) after the login,
 * however often it is refreshed. Tokens never outlive that point, and an exchange after it ends the session.</p>
 *
 * <p>Each token also carries the user's session version. Ending a user's sessions bumps it, so a
 * token issued under the old version, even by a login or exchange that raced the change, is rejected.</p>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** Error for unknown, used, revoked or expired tokens; deliberately the same for all. */
    static final String INVALID_REFRESH_TOKEN = "The refresh token is missing, invalid or expired.";

    /** Length of the {@code user_agent} column; longer values are cut. */
    static final int MAX_USER_AGENT_LENGTH = 255;

    /** Length of the {@code ip_address} column. */
    static final int MAX_IP_ADDRESS_LENGTH = 64;

    private final RefreshTokenRepository tokens;

    private final AppProperties.Jwt properties;

    private final AuditLog audit;

    private final UserRepository users;

    private final TokenRevocationService revocations;

    /**
     * Creates the service.
     *
     * @param tokens      token persistence
     * @param properties  application properties holding the lifetimes
     * @param audit       records token reuse and ended sessions
     * @param users       reads the users' session version
     * @param revocations revokes the access tokens of an ended session
     */
    public RefreshTokenService(
            RefreshTokenRepository tokens,
            AppProperties properties,
            AuditLog audit,
            UserRepository users,
            TokenRevocationService revocations) {
        this.tokens = tokens;
        this.properties = properties.jwt();
        this.audit = audit;
        this.users = users;
        this.revocations = revocations;
    }

    /**
     * Starts a new session.
     *
     * @param userId         owner of the session
     * @param sessionVersion the user's session version when the password was checked
     * @param rememberMe     whether to use the longer remember-me lifetime
     * @return the new refresh token
     * @throws BadCredentialsException if the user's sessions ended since the password was checked (mapped to 401)
     */
    @Transactional
    public IssuedRefreshToken issue(long userId, int sessionVersion, boolean rememberMe) {
        if (!isCurrent(userId, sessionVersion)) {
            throw new BadCredentialsException(INVALID_REFRESH_TOKEN);
        }
        return issue(userId, UUID.randomUUID(), sessionVersion, rememberMe, Instant.now());
    }

    /**
     * Exchanges a refresh token for a new one in the same session.
     *
     * <p>Failures do not roll back, so revoking a family on reuse is committed even though the
     * request is rejected.</p>
     *
     * @param rawToken the token presented by the client
     * @return the user the session belongs to and the successor token
     * @throws BadCredentialsException if the token is unknown, expired, already used, from ended sessions or
     *                                 from a session past its absolute lifetime (mapped to 401)
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public Rotation rotate(String rawToken) {
        RefreshToken current = tokens.findByTokenHash(SecureTokens.hash(rawToken))
                .orElseThrow(() -> new BadCredentialsException(INVALID_REFRESH_TOKEN));
        Instant now = Instant.now();
        if (current.isUsed()) {
            tokens.deleteAllByFamilyId(current.getFamilyId());
            revocations.revokeSession(current.getFamilyId());
            audit.record(AuditEvent.REFRESH_TOKEN_REUSED, current.getUserId(), "sessionId", current.getFamilyId());
            throw new BadCredentialsException(INVALID_REFRESH_TOKEN);
        }
        // Before the expiry check, so tokens issued without the cap (before it existed) are caught and audited.
        if (!now.isBefore(sessionEnd(current.getSessionStartedAt(), current.isRememberMe()))) {
            tokens.deleteAllByFamilyId(current.getFamilyId());
            audit.record(AuditEvent.SESSION_EXPIRED_ABSOLUTE, current.getUserId(), "sessionId", current.getFamilyId());
            throw new BadCredentialsException(INVALID_REFRESH_TOKEN);
        }
        if (current.isExpired(now)) {
            throw new BadCredentialsException(INVALID_REFRESH_TOKEN);
        }
        if (!isCurrent(current.getUserId(), current.getSessionVersion())) {
            tokens.deleteAllByFamilyId(current.getFamilyId());
            throw new BadCredentialsException(INVALID_REFRESH_TOKEN);
        }
        current.markUsed(now);
        // Only the newest used token is needed to detect reuse; older ones would pile up until they expire.
        tokens.deleteUsedInFamilyExcept(current.getFamilyId(), current.getTokenHash());
        return new Rotation(
                current.getUserId(),
                issue(
                        current.getUserId(),
                        current.getFamilyId(),
                        current.getSessionVersion(),
                        current.isRememberMe(),
                        current.getSessionStartedAt()));
    }

    /**
     * Ends the session a refresh token belongs to. Unknown tokens are ignored.
     *
     * @param rawToken the token presented by the client
     */
    @Transactional
    public void revokeSession(String rawToken) {
        tokens.findByTokenHash(SecureTokens.hash(rawToken))
                .ifPresent(token -> endSession(token.getUserId(), token.getFamilyId()));
    }

    /**
     * Ends a session at logout: deletes its refresh tokens and revokes all of its access tokens, not only the one
     * presented. Ending a session that has no refresh tokens left is not an error.
     *
     * @param userId    owner of the session
     * @param sessionId the session (family) id
     */
    @Transactional
    public void endSession(long userId, UUID sessionId) {
        // Without the lock, a refresh committing during the delete would leave its new token alive.
        tokens.lockAllByUserIdAndFamilyId(userId, sessionId);
        tokens.deleteAllByUserIdAndFamilyId(userId, sessionId);
        revocations.revokeSession(sessionId);
    }

    /**
     * Ends one session of a user: deletes its refresh tokens and revokes its access tokens.
     *
     * @param userId    owner of the session
     * @param sessionId the session (family) id
     * @throws ApiException 404 if the user has no such session
     */
    @Transactional
    public void revoke(long userId, UUID sessionId) {
        // Without the lock, a refresh committing during the delete would leave its new token alive.
        tokens.lockAllByUserIdAndFamilyId(userId, sessionId);
        if (tokens.deleteAllByUserIdAndFamilyId(userId, sessionId) == 0) {
            throw ApiException.notFound(ProblemType.SESSION_NOT_FOUND, "You have no session with this id.");
        }
        revocations.revokeSession(sessionId);
        audit.record(AuditEvent.SESSION_REVOKED, userId, "sessionId", sessionId);
    }

    /**
     * Ends every session of a user.
     *
     * @param userId owner of the sessions
     * @return the number of deleted tokens
     */
    @Transactional
    public int revokeAll(long userId) {
        return tokens.deleteAllByUserId(userId);
    }

    /**
     * Lists the active sessions of a user.
     *
     * @param userId owner of the sessions
     * @return one entry per session, newest first
     */
    @Transactional(readOnly = true)
    public List<Session> activeSessions(long userId) {
        return tokens.findActiveByUserId(userId, Instant.now()).stream()
                .map(token -> new Session(
                        token.getFamilyId(),
                        token.isRememberMe(),
                        token.getSessionStartedAt(),
                        token.getCreatedAt(),
                        token.getExpiresAt(),
                        token.getUserAgent(),
                        token.getIpAddress()))
                .toList();
    }

    /** Deletes expired tokens once an hour. Safe to run on several instances at once. */
    @Scheduled(cron = "0 30 * * * *")
    @Transactional
    public void deleteExpiredTokens() {
        int deleted = tokens.deleteAllExpiredBefore(Instant.now());
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }

    /**
     * Tells whether sessions issued under a session version are still valid.
     *
     * @param userId         owner of the sessions
     * @param sessionVersion the version they were issued under
     * @return {@code false} if the user is gone or their sessions ended since
     */
    private boolean isCurrent(long userId, int sessionVersion) {
        return users.findById(userId)
                .map(User::getSessionVersion)
                .filter(version -> version == sessionVersion)
                .isPresent();
    }

    /**
     * Creates and stores a token.
     *
     * @param userId         owner of the token
     * @param familyId       session the token belongs to
     * @param sessionVersion the user's session version when the session started
     * @param rememberMe     whether the session was started with "remember me"
     * @param startedAt      when the session started
     * @return the raw token with its lifetime
     */
    private IssuedRefreshToken issue(
            long userId, UUID familyId, int sessionVersion, boolean rememberMe, Instant startedAt) {
        Instant now = Instant.now();
        Duration ttl = rememberMe ? properties.rememberMeTtl() : properties.refreshTokenTtl();
        Instant sessionEnd = sessionEnd(startedAt, rememberMe);
        if (now.plus(ttl).isAfter(sessionEnd)) {
            ttl = Duration.between(now, sessionEnd);
        }
        String token = SecureTokens.generate();
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest()
                : null;
        tokens.save(new RefreshToken(
                userId,
                familyId,
                sessionVersion,
                SecureTokens.hash(token),
                rememberMe,
                now.plus(ttl),
                startedAt,
                request == null ? null : truncate(request.getHeader(HttpHeaders.USER_AGENT), MAX_USER_AGENT_LENGTH),
                request == null ? null : truncate(request.getRemoteAddr(), MAX_IP_ADDRESS_LENGTH)));
        return new IssuedRefreshToken(token, ttl, rememberMe, familyId);
    }

    /**
     * Computes when a session reaches its absolute lifetime.
     *
     * @param startedAt  when the session started (the login)
     * @param rememberMe whether it was started with "remember me"
     * @return the time after which it can no longer be refreshed
     */
    private Instant sessionEnd(Instant startedAt, boolean rememberMe) {
        return startedAt.plus(rememberMe ? properties.rememberMeMaxSessionTtl() : properties.maxSessionTtl());
    }

    /**
     * Cuts a client-supplied value to the length of its column.
     *
     * @param value     the value, may be {@code null}
     * @param maxLength the column length
     * @return the value, at most {@code maxLength} characters long
     */
    static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * A freshly issued refresh token.
     *
     * @param value      the opaque token to give to the client
     * @param ttl        how long it is valid; shorter than the configured lifetime near the session's end
     * @param rememberMe whether the session was started with "remember me"
     * @param sessionId  the session (family) the token belongs to
     */
    public record IssuedRefreshToken(String value, Duration ttl, boolean rememberMe, UUID sessionId) {}

    /**
     * Result of a successful exchange.
     *
     * @param userId    owner of the session
     * @param successor the token that replaces the exchanged one
     */
    public record Rotation(long userId, IssuedRefreshToken successor) {}

    /**
     * An active session, as listed by {@code GET /api/v1/users/me/sessions} and in the personal data export.
     *
     * @param id         the session (family) id
     * @param rememberMe whether it was started with "remember me"
     * @param startedAt  when it started (the login)
     * @param lastUsedAt when its current refresh token was issued (the login or the last refresh)
     * @param expiresAt  when it ends unless refreshed
     * @param userAgent  {@code User-Agent} of the login or last refresh, or {@code null}
     * @param ipAddress  client IP of the login or last refresh, or {@code null}
     */
    public record Session(
            UUID id,
            boolean rememberMe,
            Instant startedAt,
            Instant lastUsedAt,
            Instant expiresAt,
            String userAgent,
            String ipAddress) {}
}
