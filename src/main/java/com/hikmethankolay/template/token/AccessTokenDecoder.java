package com.hikmethankolay.template.token;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Lets the resource server accept access tokens next to JWTs: a bearer value starting with {@code pat_} is checked
 * against the database, anything else goes to the JWT decoder. Both end as a {@link Jwt}, so the rest of the
 * application does not care which one was sent.
 */
public final class AccessTokenDecoder implements JwtDecoder {

    private final JwtDecoder jwtDecoder;

    private final AccessTokenService accessTokens;

    /**
     * Creates the decoder.
     *
     * @param jwtDecoder   checks signed access tokens
     * @param accessTokens checks personal access tokens
     */
    public AccessTokenDecoder(JwtDecoder jwtDecoder, AccessTokenService accessTokens) {
        this.jwtDecoder = jwtDecoder;
        this.accessTokens = accessTokens;
    }

    /**
     * Checks a bearer value.
     *
     * @param token the value after {@code Bearer }
     * @return the caller's identity
     * @throws BadJwtException if an access token is unknown, expired, revoked or disabled
     */
    @Override
    public Jwt decode(String token) {
        if (!AccessTokenService.isAccessToken(token)) {
            return jwtDecoder.decode(token);
        }
        return accessTokens
                .authenticate(token)
                .orElseThrow(() -> new BadJwtException("The access token is unknown, expired or revoked."));
    }
}
