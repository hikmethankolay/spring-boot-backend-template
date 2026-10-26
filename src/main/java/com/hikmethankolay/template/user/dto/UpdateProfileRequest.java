package com.hikmethankolay.template.user.dto;

import com.hikmethankolay.template.user.validation.ValidEmail;
import com.hikmethankolay.template.user.validation.ValidUsername;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/users/me}. Omitted ({@code null}) fields are left unchanged.
 *
 * @param username        new username
 * @param email           new email address
 * @param currentPassword the caller's current password, required to change the email, so a stolen
 *                        token alone cannot redirect password resets
 */
public record UpdateProfileRequest(
        @ValidUsername String username,
        @ValidEmail String email,
        @Size(max = 128) String currentPassword) {}
