package com.hikmethankolay.template.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token, stored only as a SHA-256 hash (table {@code refresh_tokens}).
 *
 * <p>All tokens issued from one login share a {@link #getFamilyId() family id}; the family is
 * what a client sees as a "session".</p>
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "remember_me", nullable = false)
    private boolean rememberMe;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "session_version", nullable = false, updatable = false)
    private int sessionVersion;

    @Column(name = "session_started_at", nullable = false, updatable = false)
    private Instant sessionStartedAt;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    /** Constructor required by JPA. */
    protected RefreshToken() {}

    /**
     * Creates a new, unused token record.
     *
     * @param userId           owner of the token
     * @param familyId         session the token belongs to
     * @param sessionVersion   the user's session version when the session started
     * @param tokenHash        hex SHA-256 of the token
     * @param rememberMe       whether the session was started with "remember me"
     * @param expiresAt        when the token expires
     * @param sessionStartedAt when the session started (the login)
     * @param userAgent        {@code User-Agent} of the request that issued the token, or {@code null}
     * @param ipAddress        client IP of the request that issued the token, or {@code null}
     */
    public RefreshToken(
            long userId,
            UUID familyId,
            int sessionVersion,
            String tokenHash,
            boolean rememberMe,
            Instant expiresAt,
            Instant sessionStartedAt,
            String userAgent,
            String ipAddress) {
        this.userId = userId;
        this.sessionVersion = sessionVersion;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.rememberMe = rememberMe;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.sessionStartedAt = sessionStartedAt;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
    }

    /**
     * Returns when the session started.
     *
     * @return the time of the login that started the session
     */
    public Instant getSessionStartedAt() {
        return sessionStartedAt;
    }

    /**
     * Returns the {@code User-Agent} of the request that issued the token.
     *
     * @return the user agent, or {@code null} if the request sent none
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * Returns the client IP of the request that issued the token.
     *
     * @return the IP address, or {@code null} if unknown
     */
    public String getIpAddress() {
        return ipAddress;
    }

    /**
     * Returns the owner's id.
     *
     * @return the user id
     */
    public long getUserId() {
        return userId;
    }

    /**
     * Returns the user's session version when the session started.
     *
     * @return the session version
     */
    public int getSessionVersion() {
        return sessionVersion;
    }

    /**
     * Returns the session the token belongs to.
     *
     * @return the family id
     */
    public UUID getFamilyId() {
        return familyId;
    }

    /**
     * Tells whether the session was started with "remember me".
     *
     * @return {@code true} for remember-me sessions
     */
    public boolean isRememberMe() {
        return rememberMe;
    }

    /**
     * Returns the expiry time.
     *
     * @return when the token stops being accepted
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Returns the issue time.
     *
     * @return when the token was issued
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Tells whether the token has already been exchanged.
     *
     * @return {@code true} if the token was used
     */
    public boolean isUsed() {
        return usedAt != null;
    }

    /**
     * Tells whether the token has expired.
     *
     * @param now the current time
     * @return {@code true} if the token is no longer valid
     */
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * Marks the token as exchanged.
     *
     * @param now the current time
     */
    public void markUsed(Instant now) {
        this.usedAt = now;
    }
}
