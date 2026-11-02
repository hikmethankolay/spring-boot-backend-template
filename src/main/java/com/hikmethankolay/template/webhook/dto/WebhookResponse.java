package com.hikmethankolay.template.webhook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A webhook endpoint. The signing secret is shown only when it is created or rotated.
 *
 * @param id                  id to use in {@code /api/v1/webhooks/{id}}
 * @param url                 where events are posted
 * @param description         the label; left out if none
 * @param eventTypes          the subscribed events
 * @param enabled             whether events are sent
 * @param disabledReason      why the server turned it off (410 Gone, repeated failures); left out otherwise
 * @param consecutiveFailures failed attempts since the last success
 * @param createdAt           when it was registered
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"id", "url", "eventTypes", "enabled", "consecutiveFailures", "createdAt"})
public record WebhookResponse(
        long id,
        String url,
        String description,
        List<String> eventTypes,
        boolean enabled,
        String disabledReason,
        int consecutiveFailures,
        Instant createdAt) {}
