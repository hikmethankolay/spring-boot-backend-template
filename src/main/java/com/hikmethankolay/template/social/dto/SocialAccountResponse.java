package com.hikmethankolay.template.social.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A provider account linked to the caller. The provider's user id is left out: the caller has no use for it.
 *
 * @param id       the link's id, for unlinking it
 * @param provider the provider, {@code github} or {@code google}
 * @param linkedAt when it was linked
 */
@Schema(requiredProperties = {"id", "provider", "linkedAt"})
public record SocialAccountResponse(long id, String provider, Instant linkedAt) {}
