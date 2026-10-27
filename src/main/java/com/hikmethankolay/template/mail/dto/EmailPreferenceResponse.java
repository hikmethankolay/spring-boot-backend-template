package com.hikmethankolay.template.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One optional email category and whether the caller receives it.
 *
 * @param category    stable name, the key to send in {@code PUT /api/v1/users/me/email-preferences}
 * @param name        name to show
 * @param description what the category covers
 * @param enabled     whether the caller receives these emails
 */
@Schema(requiredProperties = {"category", "name", "description", "enabled"})
public record EmailPreferenceResponse(String category, String name, String description, boolean enabled) {}
