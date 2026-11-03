package com.hikmethankolay.template.token;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.mail.SecurityMailer;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.token.dto.AccessTokenResponse;
import com.hikmethankolay.template.token.dto.CreateAccessTokenRequest;
import com.hikmethankolay.template.token.dto.CreatedAccessTokenResponse;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.SessionsInvalidated;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, lists, revokes and checks personal access tokens.
 *
 * <p>A token is {@code pat_} followed by 43 random characters; only its SHA-256 is stored. Each request made with it
 * reads the token and its user from the database, so a revoked token, a suspended user or a demotion applies at
 * once. Every token of a user is deleted when their sessions end ({@link SessionsInvalidated}: password change or
 * reset, role change, suspension, deletion).</p>
 */
@Service
public class AccessTokenService {

    /** Prefix of every access token, so it is told apart from a JWT and found by secret scanners. */
    public static final String PREFIX = "pat_";

    /** Claim holding the token's database id in the {@link Jwt} built for a request. */
    public static final String TOKEN_ID_CLAIM = "pat_id";

    /** Claim holding the token's effective scope in the {@link Jwt} built for a request. */
    public static final String SCOPE_CLAIM = "pat_scope";

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);

    private final AccessTokenRepository tokens;

    private final UserRepository users;

    private final UserService userService;

    private final AuditLog audit;

    private final SecurityMailer securityMailer;

    private final AccessTokenProperties properties;

    /**
     * Creates the service.
     *
     * @param tokens         stores the tokens
     * @param users          loads the token's user on every request
     * @param userService    checks the password when a token is created
     * @param audit          records creation and revocation
     * @param securityMailer tells the user a token was created
     * @param properties     what tokens may do in this deployment
     */
    public AccessTokenService(
            AccessTokenRepository tokens,
            UserRepository users,
            UserService userService,
            AuditLog audit,
            SecurityMailer securityMailer,
            AccessTokenProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.userService = userService;
        this.audit = audit;
        this.securityMailer = securityMailer;
        this.properties = properties;
    }

    /**
     * Tells whether a bearer value is an access token rather than a JWT.
     *
     * @param bearer the value after {@code Bearer }
     * @return {@code true} if it starts with {@link #PREFIX}
     */
    public static boolean isAccessToken(String bearer) {
        return bearer != null && bearer.startsWith(PREFIX);
    }

    /**
     * Tells whether a request was authenticated with an access token.
     *
     * @param jwt the principal of the request
     * @return {@code true} if it was built from an access token
     */
    public static boolean isAccessToken(Jwt jwt) {
        return jwt.hasClaim(TOKEN_ID_CLAIM);
    }

    /**
     * Creates a token after checking the password.
     *
     * @param userId  the caller
     * @param request name, scope, lifetime and password
     * @return the token, shown only this once, and its details
     * @throws ApiException 400 if the password is wrong or the lifetime too long, 403 if tokens are disabled,
     *                      409 if the user has too many tokens, 429 if failed passwords locked the IP or account
     */
    @Transactional
    public CreatedAccessTokenResponse create(long userId, CreateAccessTokenRequest request) {
        requireEnabled();
        Duration lifetime = Duration.ofDays(request.expiresInDays());
        if (lifetime.compareTo(properties.maxLifetime()) > 0) {
            throw ApiException.badRequest(
                    ProblemType.ACCESS_TOKEN_LIFETIME_TOO_LONG,
                    "A token may live at most " + properties.maxLifetime().toDays() + " days.");
        }
        // Serializes with a password change and with other creates: the check, count and insert below see its result.
        users.findLockedById(userId);
        userService.confirmPassword(userId, request.currentPassword());
        Instant now = Instant.now();
        if (tokens.countByUserIdAndExpiresAtAfter(userId, now) >= properties.maxPerUser()) {
            throw ApiException.conflict(
                    ProblemType.ACCESS_TOKEN_LIMIT_REACHED,
                    "You have " + properties.maxPerUser() + " tokens; revoke one before creating another.");
        }
        String value = PREFIX + SecureTokens.generate();
        AccessToken token = tokens.save(new AccessToken(
                userId, request.name().strip(), SecureTokens.hash(value), request.scope(), now.plus(lifetime)));
        audit.record(AuditEvent.ACCESS_TOKEN_CREATED, userId, "tokenId", token.getId(), "scope", token.getScope());
        users.findById(userId)
                .ifPresent(user -> securityMailer.accessTokenCreated(
                        user.getEmail(), token.getName(), token.getScope().name(), token.getExpiresAt()));
        return new CreatedAccessTokenResponse(value, toResponse(token));
    }

    /**
     * Lists the caller's unexpired tokens.
     *
     * @param userId the caller
     * @return the tokens, newest first
     */
    @Transactional(readOnly = true)
    public List<AccessTokenResponse> list(long userId) {
        return tokens.findByUserIdAndExpiresAtAfterOrderByIdDesc(userId, Instant.now()).stream()
                .map(AccessTokenService::toResponse)
                .toList();
    }

    /**
     * Revokes one of the caller's tokens; it stops working at once.
     *
     * @param userId the caller
     * @param id     the token
     * @throws ApiException 404 if the caller has no such token
     */
    @Transactional
    public void revoke(long userId, long id) {
        if (tokens.deleteByIdAndUserId(id, userId) == 0) {
            throw ApiException.notFound(ProblemType.ACCESS_TOKEN_NOT_FOUND, "You have no access token with this id.");
        }
        audit.record(AuditEvent.ACCESS_TOKEN_REVOKED, userId, "tokenId", id);
    }

    /**
     * Deletes every token of a user whose sessions end, inside the transaction that ended them.
     *
     * @param event identifies the user
     */
    @EventListener
    public void revokeAll(SessionsInvalidated event) {
        // The changed user row is written only at commit: lock it now, so a create in flight finishes first and its
        // token is deleted below, or starts after the change and sees it.
        users.findLockedById(event.userId());
        int revoked = tokens.deleteAllByUserId(event.userId());
        if (revoked > 0) {
            audit.record(AuditEvent.ACCESS_TOKENS_REVOKED, event.userId(), "accessTokens", revoked);
        }
    }

    /**
     * Checks a token and describes the request it authenticates as a {@link Jwt}, so controllers read the caller
     * with {@code @AuthenticationPrincipal Jwt} whatever credential was sent.
     *
     * @param value the token from the {@code Authorization} header
     * @return the caller's identity, or empty if tokens are disabled or the token is unknown, expired or its user
     *         suspended
     */
    @Transactional
    public Optional<Jwt> authenticate(String value) {
        if (!properties.enabled() || !isAccessToken(value)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        // shortcut: two primary-key reads per request; cache them in Redis if token traffic grows large.
        return tokens.findByTokenHash(SecureTokens.hash(value))
                .filter(token -> token.getExpiresAt().isAfter(now))
                .flatMap(token -> users.findById(token.getUserId())
                        .filter(user -> user.getSuspendedAt() == null)
                        .map(user -> {
                            tokens.touch(token.getId(), now, now.minus(TOUCH_INTERVAL));
                            return toJwt(token, user);
                        }));
    }

    /** Deletes expired tokens once an hour. Safe to run on several instances at once. */
    @Scheduled(cron = "0 35 * * * *")
    @Transactional
    public void deleteExpiredTokens() {
        int deleted = tokens.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Deleted {} expired access tokens", deleted);
        }
    }

    /**
     * Returns the role a token of this user acts with: theirs, capped at {@code app.access-tokens.max-role}.
     *
     * @param role the user's role
     * @return the lower of the two
     */
    Role effectiveRole(Role role) {
        return role.compareTo(properties.maxRole()) > 0 ? properties.maxRole() : role;
    }

    /**
     * Returns the scope a token acts with: its own, or {@code READ} when write tokens are disabled.
     *
     * @param scope the scope it was created with
     * @return the scope that applies now
     */
    AccessTokenScope effectiveScope(AccessTokenScope scope) {
        return properties.allowWriteScope() ? scope : AccessTokenScope.READ;
    }

    private Jwt toJwt(AccessToken token, User user) {
        return Jwt.withTokenValue(PREFIX + token.getId())
                .header("alg", "none")
                .header("typ", "pat")
                .subject(Long.toString(user.getId()))
                .issuedAt(token.getCreatedAt())
                .expiresAt(token.getExpiresAt())
                .claim(TokenService.USERNAME_CLAIM, user.getUsername())
                .claim(
                        TokenService.ROLES_CLAIM,
                        List.of(effectiveRole(user.getRole()).name()))
                .claim(TOKEN_ID_CLAIM, token.getId())
                .claim(SCOPE_CLAIM, effectiveScope(token.getScope()).name())
                .build();
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ProblemType.ACCESS_TOKENS_DISABLED,
                    "Personal access tokens are turned off on this server.");
        }
    }

    private static AccessTokenResponse toResponse(AccessToken token) {
        return new AccessTokenResponse(
                token.getId(),
                token.getName(),
                token.getScope(),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getLastUsedAt());
    }
}
