package com.hikmethankolay.template.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A self-service sign-up waiting for its email link (table {@code pending_registrations}). The account is created
 * from it when the link is opened; until then nothing about the address or the username is visible to anyone.
 * The token is stored only as a SHA-256 hash and the password only as its encoded hash.
 */
@Entity
@Table(name = "pending_registrations")
public class PendingRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String username;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Constructor required by JPA. */
    protected PendingRegistration() {}

    /**
     * Creates a pending registration.
     *
     * @param username     the requested username
     * @param email        the normalised email address
     * @param passwordHash the encoded password
     * @param tokenHash    hex SHA-256 of the token in the email
     * @param expiresAt    when the link expires
     */
    public PendingRegistration(
            String username, String email, String passwordHash, String tokenHash, Instant expiresAt) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    /**
     * Returns the requested username.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the normalised email address.
     *
     * @return the email
     */
    public String getEmail() {
        return email;
    }

    /**
     * Returns the encoded password.
     *
     * @return the password hash
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Tells whether the link has expired.
     *
     * @param now the current time
     * @return {@code true} if the registration can no longer be completed
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
