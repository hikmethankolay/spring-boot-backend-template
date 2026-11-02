package com.hikmethankolay.template.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A provider account linked to a user ({@code social_identities} table). */
@Entity
@Table(name = "social_identities")
public class SocialIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false, length = 20)
    private String provider;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Constructor required by JPA. */
    protected SocialIdentity() {}

    /**
     * Links a provider account to a user.
     *
     * @param userId   the user
     * @param provider registration id, e.g. {@code github}
     * @param subject  the provider's user id
     */
    public SocialIdentity(long userId, String provider, String subject) {
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
    }

    /**
     * Returns the id of the link.
     *
     * @return the id, or {@code null} before it is saved
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the linked user.
     *
     * @return the user id
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * Returns the provider.
     *
     * @return the registration id
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Returns the provider's user id.
     *
     * @return the subject
     */
    public String getSubject() {
        return subject;
    }

    /**
     * Returns when the account was linked.
     *
     * @return the creation time
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
