package com.hikmethankolay.template.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code PATCH /api/v1/webhooks/{id}}; fields left out keep their value.
 *
 * @param url         a new URL
 * @param description a new label
 * @param eventTypes  a new set of events
 * @param enabled     {@code false} turns the endpoint off (its pending deliveries are given up); {@code true} turns it
 *                    back on and clears its failure count
 */
public record WebhookUpdateRequest(
        @Size(max = 2048) String url,
        @Size(max = 200) String description,
        @Size(min = 1, max = 50) List<@NotBlank String> eventTypes,
        Boolean enabled) {}
