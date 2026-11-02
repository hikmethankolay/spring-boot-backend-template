package com.hikmethankolay.template.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/users/me/mfa/totp/recovery-codes}. Both factors are required, so neither a stolen token
 * nor a stolen password alone can get a fresh set of codes that bypass the authenticator.
 *
 * @param currentPassword the caller's password
 * @param code            a code from the authenticator app; recovery codes are not accepted
 */
public record RegenerateRecoveryCodesRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 32) String code) {}
