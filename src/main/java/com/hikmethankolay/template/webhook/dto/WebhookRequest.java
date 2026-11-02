package com.hikmethankolay.template.webhook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /api/v1/webhooks}.
 *
 * @param url         where events are posted; {@code https://} (and {@code http://} unless the server requires HTTPS)
 *                    to a public address
 * @param description a label to recognise the endpoint by
 * @param eventTypes  the events to receive, e.g. {@code note.created}
 */
public record WebhookRequest(
        @Schema(example = "https://example.com/webhooks") @NotBlank @Size(max = 2048)
        String url,

        @Size(max = 200) String description,

        @Schema(example = "[\"note.created\"]") @NotEmpty @Size(max = 50)
        List<@NotBlank String> eventTypes) {}
