package com.hikmethankolay.template.auth.dto;

import com.hikmethankolay.template.user.validation.ValidPassword;
import com.hikmethankolay.template.user.validation.ValidUsername;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/register/complete}: the second step of a sign-up, sent from the page the link
 * in the sign-up email opens.
 *
 * @param token    the token from the link
 * @param username unique username; see {@link ValidUsername}
 * @param password password; see {@link ValidPassword}
 */
public record CompleteRegistrationRequest(
        @NotBlank @Size(max = 2048) String token,
        @NotBlank @ValidUsername String username,
        @NotBlank @ValidPassword String password) {}
