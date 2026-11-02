package com.hikmethankolay.template.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** A personal access token. Only the SHA-256 of the token is stored. */
@Entity
@Table(name = "access_tokens")
public class AccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 100, updatable = false)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AccessTokenScope scope;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Constructor required by JPA. */
    protected AccessToken() {}

    /**
     * Creates a new, not yet persisted token.
     *
     * @param userId    id of the owner
     * @param name      the owner's label for it
     * @param tokenHash SHA-256 of the token
     * @param scope     what it may do
     * @param expiresAt when it stops working
     */
    public AccessToken(long userId, String name, String tokenHash, AccessTokenScope scope, Instant expiresAt) {
        this.userId = userId;
        this.name = name;
        this.tokenHash = tokenHash;
        this.scope = scope;
        this.expiresAt = expiresAt;
    }

    /** Sets the creation time before the insert. */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Returns the id.
     *
     * @return the database id, or {@code null} before the token is persisted
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the owner's id.
     *
     * @return the id of the user the token acts as
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * Returns the label.
     *
     * @return the name the owner gave the token
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the scope.
     *
     * @return what the token may do
     */
    public AccessTokenScope getScope() {
        return scope;
    }

    /**
     * Returns the expiry.
     *
     * @return when the token stops working
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Returns the last use.
     *
     * @return when the token was last used, to the minute, or {@code null} if never
     */
    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    /**
     * Returns the creation time.
     *
     * @return when the token was created
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
