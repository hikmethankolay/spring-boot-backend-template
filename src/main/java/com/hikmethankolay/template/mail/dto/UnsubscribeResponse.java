package com.hikmethankolay.template.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an unsubscribe link turns off, for the page that asks to confirm.
 *
 * @param category     stable name of the category
 * @param categoryName name to show
 * @param enabled      whether the recipient still receives these emails
 */
@Schema(requiredProperties = {"category", "categoryName", "enabled"})
public record UnsubscribeResponse(String category, String categoryName, boolean enabled) {}
