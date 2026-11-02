package com.hikmethankolay.template.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A URL that receives a user's webhook events. The signing secrets are stored encrypted.
 *
 * <p>Updates write only the changed columns ({@code @DynamicUpdate}): the sender records failures while the owner may
 * edit the URL, and neither overwrites the other.</p>
 */
@Entity
@DynamicUpdate
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 200)
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", nullable = false, columnDefinition = "text[]")
    private List<String> eventTypes = new ArrayList<>();

    @Column(nullable = false, length = 200)
    private String secret;

    @Column(name = "previous_secret", length = 200)
    private String previousSecret;

    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "disabled_reason", length = 200)
    private String disabledReason;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Constructor required by JPA. */
    protected WebhookEndpoint() {}

    /**
     * Creates a new, not yet persisted endpoint.
     *
     * @param userId      the owner
     * @param url         where events are sent
     * @param description the owner's label, may be {@code null}
     * @param eventTypes  the subscribed event names
     */
    public WebhookEndpoint(long userId, String url, String description, List<String> eventTypes) {
        this.userId = userId;
        this.url = url;
        this.description = description;
        this.eventTypes = new ArrayList<>(eventTypes);
    }

    /** Sets the creation time before the insert. */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Replaces the signing secret, keeping the old one valid for a while.
     *
     * @param encrypted    the new secret, encrypted
     * @param keepOldUntil until when the old secret also signs, or {@code null} to drop it at once
     */
    public void rotateSecret(String encrypted, Instant keepOldUntil) {
        if (secret != null && keepOldUntil != null) {
            previousSecret = secret;
            previousSecretExpiresAt = keepOldUntil;
        }
        secret = encrypted;
    }

    /** Counts a successful delivery: the failure streak ends. */
    public void recordSuccess() {
        consecutiveFailures = 0;
    }

    /**
     * Counts a failed attempt.
     *
     * @return the number of failed attempts in a row
     */
    public int recordFailure() {
        return ++consecutiveFailures;
    }

    /**
     * Turns the endpoint off because deliveries keep failing or the receiver asked to stop.
     *
     * @param reason shown to the owner
     */
    public void disable(String reason) {
        enabled = false;
        disabledReason = reason;
    }

    /**
     * Turns the endpoint on or off at its owner's request. Turning it on clears the failure streak.
     *
     * @param enabled whether events are sent
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        disabledReason = null;
        if (enabled) {
            consecutiveFailures = 0;
        }
    }

    /**
     * Returns the id.
     *
     * @return the database id, or {@code null} before the endpoint is persisted
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the owner's id.
     *
     * @return the user who registered the endpoint
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * Returns the URL.
     *
     * @return where events are sent
     */
    public String getUrl() {
        return url;
    }

    /**
     * Changes the URL.
     *
     * @param url a validated URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the label.
     *
     * @return the owner's description, or {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Changes the label.
     *
     * @param description the new description, or {@code null}
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the subscriptions.
     *
     * @return the subscribed event names
     */
    public List<String> getEventTypes() {
        return List.copyOf(eventTypes);
    }

    /**
     * Replaces the subscriptions.
     *
     * @param eventTypes validated event names
     */
    public void setEventTypes(List<String> eventTypes) {
        this.eventTypes = new ArrayList<>(eventTypes);
    }

    /**
     * Returns the current secret.
     *
     * @return the encrypted signing secret
     */
    public String getSecret() {
        return secret;
    }

    /**
     * Returns the previous secret while it still signs.
     *
     * @param now the current time
     * @return the encrypted previous secret, or {@code null} if there is none or it expired
     */
    public String getPreviousSecret(Instant now) {
        return previousSecretExpiresAt != null && previousSecretExpiresAt.isAfter(now) ? previousSecret : null;
    }

    /**
     * Tells whether events are sent.
     *
     * @return {@code true} while enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns why the application turned the endpoint off.
     *
     * @return the reason, or {@code null}
     */
    public String getDisabledReason() {
        return disabledReason;
    }

    /**
     * Returns the failure streak.
     *
     * @return failed attempts since the last success
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Returns the creation time.
     *
     * @return when the endpoint was registered
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
