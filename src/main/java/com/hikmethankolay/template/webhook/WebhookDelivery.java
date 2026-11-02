package com.hikmethankolay.template.webhook;

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

/** One event queued for one endpoint, with the outcome of its attempts. */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    /** Where a delivery stands. */
    public enum Status {
        /** Waiting for its next attempt. */
        PENDING,
        /** The receiver answered 2xx. */
        SENT,
        /** Every attempt failed, or the endpoint was turned off. */
        FAILED
    }

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private Long endpointId;

    @Column(name = "message_id", nullable = false, length = 40, updatable = false)
    private String messageId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Constructor required by JPA. */
    protected WebhookDelivery() {}

    /**
     * Queues an event for an endpoint, due at once.
     *
     * @param endpointId the receiving endpoint
     * @param messageId  the {@code webhook-id}, shared by every attempt
     * @param eventType  e.g. {@code note.created}
     * @param payload    the JSON body
     */
    public WebhookDelivery(long endpointId, String messageId, String eventType, String payload) {
        this.endpointId = endpointId;
        this.messageId = messageId;
        this.eventType = eventType;
        this.payload = payload;
        this.nextAttemptAt = Instant.now();
    }

    /** Sets the creation time before the insert. */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Moves the due time forward while a sender works on the delivery, so no other sender picks it up.
     *
     * @param until when it is due again if the sender never reports back
     */
    public void lease(Instant until) {
        nextAttemptAt = until;
    }

    /**
     * Records a 2xx answer.
     *
     * @param status the HTTP status
     * @param now    the current time
     */
    public void markSent(int status, Instant now) {
        attempts++;
        this.status = Status.SENT;
        responseStatus = status;
        lastError = null;
        finishedAt = now;
    }

    /**
     * Records a failed attempt and schedules the next one.
     *
     * @param status  the HTTP status, or {@code null} if there was no answer
     * @param error   what went wrong
     * @param nextTry when to try again
     */
    public void markRetry(Integer status, String error, Instant nextTry) {
        attempts++;
        responseStatus = status;
        lastError = truncate(error);
        nextAttemptAt = nextTry;
    }

    /**
     * Records a failed attempt after which no more are made.
     *
     * @param status the HTTP status, or {@code null}
     * @param error  what went wrong
     * @param now    the current time
     */
    public void markFailed(Integer status, String error, Instant now) {
        attempts++;
        this.status = Status.FAILED;
        responseStatus = status;
        lastError = truncate(error);
        finishedAt = now;
    }

    /**
     * Queues the delivery again with a fresh set of attempts.
     *
     * @param now the current time
     */
    public void requeue(Instant now) {
        status = Status.PENDING;
        attempts = 0;
        nextAttemptAt = now;
        finishedAt = null;
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * Returns the id.
     *
     * @return the database id
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the endpoint.
     *
     * @return the id of the receiving endpoint
     */
    public Long getEndpointId() {
        return endpointId;
    }

    /**
     * Returns the message id.
     *
     * @return the {@code webhook-id} header value
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * Returns the event type.
     *
     * @return e.g. {@code note.created}
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Returns the body.
     *
     * @return the JSON that is signed and sent
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Returns the status.
     *
     * @return where the delivery stands
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Returns the number of attempts.
     *
     * @return attempts made so far
     */
    public int getAttempts() {
        return attempts;
    }

    /**
     * Returns the next attempt time.
     *
     * @return when the delivery is due while pending
     */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * Returns the last HTTP status.
     *
     * @return the receiver's last answer, or {@code null}
     */
    public Integer getResponseStatus() {
        return responseStatus;
    }

    /**
     * Returns the last error.
     *
     * @return what went wrong last, or {@code null}
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Returns the creation time.
     *
     * @return when the event was queued
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the end time.
     *
     * @return when it was sent or gave up, or {@code null} while pending
     */
    public Instant getFinishedAt() {
        return finishedAt;
    }
}
