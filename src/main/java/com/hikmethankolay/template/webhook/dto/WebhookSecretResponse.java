package com.hikmethankolay.template.webhook.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A new endpoint, or an endpoint with a new signing secret.
 *
 * @param secret   the {@code whsec_...} secret receivers verify signatures with (Standard Webhooks); shown only now
 * @param endpoint the endpoint
 */
@Schema(requiredProperties = {"secret", "endpoint"})
public record WebhookSecretResponse(
        @Schema(example = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")
        String secret,

        WebhookResponse endpoint) {}
