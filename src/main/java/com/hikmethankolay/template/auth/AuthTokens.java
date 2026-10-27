package com.hikmethankolay.template.auth;

import com.hikmethankolay.template.auth.RefreshTokenService.IssuedRefreshToken;
import com.hikmethankolay.template.security.TokenService.IssuedToken;

/**
 * The tokens returned by login and refresh.
 *
 * @param accessToken  short-lived JWT for API calls
 * @param refreshToken long-lived, single-use token for getting the next access token
 * @param deviceToken  value of the device cookie that recognizes the browser at its next login ({@link KnownDevices});
 *                     {@code null} on refresh, and for a client recognized without it
 */
public record AuthTokens(IssuedToken accessToken, IssuedRefreshToken refreshToken, String deviceToken)
        implements LoginResult {}
