package com.hikmethankolay.template.security;

import com.hikmethankolay.template.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Denylist of revoked JWTs, kept in Redis so revocation works across all instances.
 *
 * <p>Two kinds of entries exist, both kept only until the affected tokens would have expired
 * anyway, so the list never grows beyond the tokens that are still alive:</p>
 * <ul>
 *   <li>a per-user cut-off time: every token of that user issued before it is rejected. Set when
 *       all sessions of a user end, e.g. after a password change.</li>
 *   <li>a session id ({@code sid}): every token of that session is rejected. Set when one session ends: at
 *       logout, through {@code DELETE /api/v1/users/me/sessions/{id}} or because its refresh token was reused.</li>
 * </ul>
 *
 * <p>The JWT decoder consults both on every request, in one Redis round trip (see {@link SecurityConfig}).</p>
 *
 * <p>If Redis is unavailable, revocation and the check fail open (tokens stay valid) and a warning
 * is logged: an outage briefly disables revocation rather than locking every user out.</p>
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final StringRedisTemplate redis;

    private final String userKeyPrefix;

    private final String sessionKeyPrefix;

    private final Duration accessTokenTtl;

    /**
     * Creates the service.
     *
     * @param redis           Redis access
     * @param applicationName value of {@code spring.application.name}, used to namespace keys
     * @param properties      application properties holding the access token lifetime
     */
    public TokenRevocationService(
            StringRedisTemplate redis,
            @Value("${spring.application.name}") String applicationName,
            AppProperties properties) {
        this.redis = redis;
        this.userKeyPrefix = applicationName + ":tokens-revoked-before:";
        this.sessionKeyPrefix = applicationName + ":revoked-session:";
        this.accessTokenTtl = properties.jwt().accessTokenTtl();
    }

    /**
     * Revokes every access token of a user issued so far. Tokens issued afterwards stay valid
     * (see {@link #issuedNotBefore(long)}).
     *
     * <p>Token issue times have whole-second precision, so the cut-off is the start of the next
     * second: tokens issued in the current second are revoked too.</p>
     *
     * @param userId the user whose tokens are revoked
     */
    public void revokeAllIssuedBefore(long userId) {
        long cutOff = Instant.now().getEpochSecond() + 1;
        try {
            // Kept one access token lifetime: by then every token issued before the cut-off has expired.
            redis.opsForValue().set(userKeyPrefix + userId, Long.toString(cutOff), accessTokenTtl.plusSeconds(1));
        } catch (DataAccessException ex) {
            log.warn("Could not revoke the tokens of user {}: {}", userId, ex.getMessage());
        }
    }

    /**
     * Revokes every access token of a session issued so far. Sessions do not continue after this,
     * so no later token carries the id.
     *
     * @param sessionId the session whose tokens are revoked
     */
    public void revokeSession(UUID sessionId) {
        try {
            // Kept one access token lifetime: by then every token of the session has expired.
            redis.opsForValue().set(sessionKeyPrefix + sessionId, "1", accessTokenTtl.plusSeconds(1));
        } catch (DataAccessException ex) {
            log.warn("Could not revoke the tokens of session {}: {}", sessionId, ex.getMessage());
        }
    }

    /**
     * Returns the earliest issue time a new token of the user may have, so it is not caught by a
     * cut-off set in the current second.
     *
     * @param userId the user a token is issued for
     * @return the active cut-off of the user, or empty if there is none (or Redis is unavailable)
     */
    public Optional<Instant> issuedNotBefore(long userId) {
        try {
            String cutOff = redis.opsForValue().get(userKeyPrefix + userId);
            return Optional.ofNullable(cutOff).map(value -> Instant.ofEpochSecond(Long.parseLong(value)));
        } catch (DataAccessException ex) {
            log.warn("Could not read the token cut-off of user {}: {}", userId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Tells whether a token has been revoked with its session or by a cut-off of its user.
     *
     * @param jwt the token, already verified otherwise
     * @return {@code true} if the token must be rejected
     */
    public boolean isRevoked(Jwt jwt) {
        String userId = jwt.getSubject();
        if (userId == null) {
            return false;
        }
        try {
            String sessionId = jwt.getClaimAsString(TokenService.SESSION_CLAIM);
            List<String> entries = redis.opsForValue()
                    .multiGet(List.of(
                            userKeyPrefix + userId,
                            // Tokens from before sessions were listed have no sid; nothing can revoke it.
                            sessionKeyPrefix + Objects.requireNonNullElse(sessionId, "none")));
            if (entries == null) {
                return false;
            }
            if (sessionId != null && entries.get(1) != null) {
                return true;
            }
            Instant issuedAt = jwt.getIssuedAt();
            return entries.get(0) != null
                    && issuedAt != null
                    && issuedAt.getEpochSecond() < Long.parseLong(entries.get(0));
        } catch (DataAccessException ex) {
            log.warn("Could not check token revocation for user {}: {}", userId, ex.getMessage());
            return false;
        }
    }
}
