package com.hikmethankolay.template.user.dto;

import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.validation.ValidEmail;
import com.hikmethankolay.template.user.validation.ValidPassword;
import com.hikmethankolay.template.user.validation.ValidUsername;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/users/{id}} (administrators only). Omitted ({@code null}) fields are left unchanged.
 *
 * @param username new username
 * @param email    new email address
 * @param password new password; the current one is not required
 * @param role            new role; only the owner can change roles, and {@code OWNER} cannot be assigned
 * @param currentPassword the caller's own current password; required when {@code role} changes
 */
public record AdminUpdateUserRequest(
        @ValidUsername String username,
        @ValidEmail String email,
        @ValidPassword String password,

        @Schema(
                allowableValues = {"USER", "ADMIN"},
                description = "New role. Only the owner can change roles, confirmed with `currentPassword`")
        Role role,

        @Schema(description = "The caller's own current password; required when `role` changes") @Size(max = 128)
        String currentPassword) {}
