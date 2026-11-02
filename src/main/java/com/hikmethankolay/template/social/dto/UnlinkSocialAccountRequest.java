package com.hikmethankolay.template.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code DELETE /api/v1/users/me/social-accounts/{id}}.
 *
 * @param currentPassword the caller's password
 */
public record UnlinkSocialAccountRequest(
        @NotBlank @Size(max = 128) String currentPassword) {}
