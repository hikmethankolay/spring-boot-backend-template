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
 * @param code            a code from the authenticator app; required when two-factor authentication is on
 */
public record CreateAccessTokenRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull AccessTokenScope scope,
        @Schema(example = "90") @Min(1) @Max(3650) int expiresInDays,
        @NotBlank @Size(max = 128) String currentPassword,

        @Schema(example = "123456", description = "Required when two-factor authentication is on") @Size(max = 20)
        String code) {

    /**
     * A request without an authenticator code, for users without two-factor authentication.
     *
     * @param name            a label to recognise the token by
     * @param scope           what the token may do
     * @param expiresInDays   lifetime in days
     * @param currentPassword the caller's password
     */
    public CreateAccessTokenRequest(String name, AccessTokenScope scope, int expiresInDays, String currentPassword) {
        this(name, scope, expiresInDays, currentPassword, null);
    }
}
