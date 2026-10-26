package com.hikmethankolay.template.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code DELETE /api/v1/users/me/mfa/totp}. Both factors are required, so neither a stolen token nor a
 * stolen password alone can turn two-factor authentication off.
 *
 * @param currentPassword the caller's password
 * @param code            a code from the authenticator app, or an unused recovery code
 */
public record DisableTotpRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 32) String code) {}
