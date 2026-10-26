package com.hikmethankolay.template.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/users/me/mfa/totp/confirm}. The password is required, so a stolen token alone cannot
 * put an attacker's authenticator on the account.
 *
 * @param currentPassword the caller's password
 * @param code            the 6-digit code currently shown by the authenticator app
 */
public record ConfirmTotpRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 32) String code) {}
