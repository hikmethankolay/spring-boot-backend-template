package com.hikmethankolay.template.token.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A new access token.
 *
 * @param token   the token to send as {@code Authorization: Bearer <token>}; shown only this once, store it now
 * @param details the token's id, name, scope and lifetime
 */
@Schema(requiredProperties = {"token", "details"})
public record CreatedAccessTokenResponse(
        @Schema(example = "pat_8xJ2kQ...") String token, AccessTokenResponse details) {}
