package com.hikmethankolay.template.token.dto;

import com.hikmethankolay.template.token.AccessTokenScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/users/me/access-tokens}.
 *
 * @param name            a label to recognise the token by, e.g. {@code backup script}
 * @param scope           {@code READ} for {@code GET} requests only, {@code WRITE} for every method
 * @param expiresInDays   lifetime in days, up to {@code app.access-tokens.max-lifetime}
 * @param currentPassword the caller's password, so a stolen access token alone cannot create a long-lived one
 */
public record CreateAccessTokenRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull AccessTokenScope scope,
        @Schema(example = "90") @Min(1) @Max(3650) int expiresInDays,
        @NotBlank @Size(max = 128) String currentPassword) {}
