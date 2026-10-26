package com.hikmethankolay.template.user.dto;

import com.hikmethankolay.template.user.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/users/me/password}.
 *
 * @param currentPassword the password in use, to prove the caller knows it
 * @param newPassword     the new password; must satisfy {@link ValidPassword}
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @ValidPassword String newPassword) {}
