package com.hikmethankolay.template.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a broadcast would send, without sending it.
 *
 * @param recipients users who would get it: the audience, minus those who turned the category off
 * @param subject    subject line
 * @param text       the plain-text body as rendered, with a sample unsubscribe link
 */
@Schema(requiredProperties = {"recipients", "subject", "text"})
public record BroadcastPreview(int recipients, String subject, String text) {}
