package com.hikmethankolay.template.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An email waiting to be sent (table {@code email_outbox}). Rows are deleted once sent or given up.
 */
@Entity
@Table(name = "email_outbox")
public class OutboxEmail {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String recipient;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "html_body", columnDefinition = "text")
    private String htmlBody;

    @Column(name = "unsubscribe_token", length = 200)
    private String unsubscribeToken;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Constructor required by JPA. */
    protected OutboxEmail() {}

    /**
     * Queues a new email for immediate sending.
     *
     * @param recipient recipient address
     * @param subject   subject line
     * @param body      plain-text body
     */
    public OutboxEmail(String recipient, String subject, String body) {
        this(recipient, subject, body, null);
    }

    /**
     * Queues a new email with an optional HTML alternative for immediate sending.
     *
     * @param recipient recipient address
     * @param subject   subject line
     * @param body      plain-text body
     * @param htmlBody  HTML alternative of the body, or {@code null} for a plain-text email
     */
    public OutboxEmail(String recipient, String subject, String body, String htmlBody) {
        this(recipient, subject, body, htmlBody, null);
    }

    /**
     * Queues a new email in an optional category for immediate sending.
     *
     * @param recipient        recipient address
     * @param subject          subject line
     * @param body             plain-text body
     * @param htmlBody         HTML alternative of the body, or {@code null} for a plain-text email
     * @param unsubscribeToken token for the {@code List-Unsubscribe} header, or {@code null} for transactional email
     */
    public OutboxEmail(String recipient, String subject, String body, String htmlBody, String unsubscribeToken) {
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.htmlBody = htmlBody;
        this.unsubscribeToken = unsubscribeToken;
        this.createdAt = Instant.now();
        this.nextAttemptAt = createdAt;
    }

    /**
     * Returns the id.
     *
     * @return the database id, or {@code null} before the email is persisted
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the recipient.
     *
     * @return the recipient address
     */
    public String getRecipient() {
        return recipient;
    }

    /**
     * Returns the subject.
     *
     * @return the subject line
     */
    public String getSubject() {
        return subject;
    }

    /**
     * Returns the body.
     *
     * @return the plain-text body
     */
    public String getBody() {
        return body;
    }

    /**
     * Returns the HTML body.
     *
     * @return the HTML alternative of the body, or {@code null} for a plain-text email
     */
    public String getHtmlBody() {
        return htmlBody;
    }

    /**
     * Returns the unsubscribe token.
     *
     * @return the token for the {@code List-Unsubscribe} header, or {@code null} for transactional email
     */
    public String getUnsubscribeToken() {
        return unsubscribeToken;
    }

    /**
     * Returns the number of failed attempts.
     *
     * @return failed attempts so far
     */
    public int getAttempts() {
        return attempts;
    }

    /**
     * Returns when the next attempt is due.
     *
     * @return the earliest time of the next attempt
     */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * Records a failed attempt and schedules the next one.
     *
     * @param error         what went wrong
     * @param nextAttemptAt earliest time of the next attempt
     */
    public void recordFailure(String error, Instant nextAttemptAt) {
        this.attempts++;
        this.lastError =
                error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        this.nextAttemptAt = nextAttemptAt;
    }
}
