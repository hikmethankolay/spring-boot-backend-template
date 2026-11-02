package com.hikmethankolay.template.webhook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One event sent, or being sent, to an endpoint.
 *
 * @param id             id to use in {@code POST /api/v1/webhooks/{id}/deliveries/{deliveryId}/retry}
 * @param messageId      the {@code webhook-id} header, the same for every attempt
 * @param eventType      e.g. {@code note.created}
 * @param status         {@code PENDING}, {@code SENT} or {@code FAILED}
 * @param attempts       attempts made
 * @param responseStatus the receiver's last HTTP status; left out if it never answered
 * @param lastError      what went wrong last; left out after a success
 * @param createdAt      when the event was queued
 * @param nextAttemptAt  when the next attempt is due; only while pending
 * @param finishedAt     when it was sent or given up; left out while pending
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"id", "messageId", "eventType", "status", "attempts", "createdAt"})
public record WebhookDeliveryResponse(
        long id,
        String messageId,
        String eventType,
        String status,
        int attempts,
        Integer responseStatus,
        String lastError,
        Instant createdAt,
        Instant nextAttemptAt,
        Instant finishedAt) {}
