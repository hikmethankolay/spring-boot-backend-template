package com.hikmethankolay.template.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login}.
 *
 * @param identifier username or email address
 * @param password   plain password
 * @param rememberMe optional; {@code true} keeps the session for {@code app.jwt.remember-me-ttl} and uses persistent cookies
 */
public record LoginRequest(
        @NotBlank @Size(max = 254) String identifier,
        @NotBlank @Size(max = 128) String password,
        Boolean rememberMe) {

    /**
     * Tells whether "remember me" was requested.
     *
     * @return {@code true} only if {@code rememberMe} is explicitly {@code true}
     */
    public boolean isRememberMe() {
        return Boolean.TRUE.equals(rememberMe);
    }
}
