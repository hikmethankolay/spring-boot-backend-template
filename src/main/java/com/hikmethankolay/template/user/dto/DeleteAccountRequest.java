package com.hikmethankolay.template.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code DELETE /api/v1/users/me}, and of {@code DELETE /api/v1/users/{id}} when the owner
 * deletes an administrator.
 *
 * @param password the caller's current password, so a stolen token alone cannot delete an account
 */
public record DeleteAccountRequest(
        @NotBlank @Size(max = 128) String password) {}
