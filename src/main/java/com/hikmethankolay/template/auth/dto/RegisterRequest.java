package com.hikmethankolay.template.auth.dto;

import com.hikmethankolay.template.user.validation.ValidEmail;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/auth/register}: the first step of an email-first sign-up.
 *
 * @param email the address to send the sign-up link to
 */
public record RegisterRequest(@NotBlank @ValidEmail String email) {}
