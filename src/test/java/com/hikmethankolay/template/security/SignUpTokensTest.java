package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.user.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

/** Unit tests of {@link SignUpTokens}: the token in a sign-up link. */
class SignUpTokensTest {

    private final SecurityConfig config = new SecurityConfig();
    private final AppProperties properties = TestProperties.create();
    private final JwtKeys keys = new JwtKeys(properties);
    private final SignUpTokens tokens = new SignUpTokens(config.jwtEncoder(keys), keys, properties);

    @Test
    void aTokenCarriesTheAddressItWasIssuedFor() {
        assertThat(tokens.verify(tokens.issue("alice@example.com"))).contains("alice@example.com");
    }

    @Test
    void forgedAndExpiredTokensAreRejected() {
        String token = tokens.issue("alice@example.com");
        Instant now = Instant.now();
        String expired = config.jwtEncoder(keys)
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(JwtKeys.algorithm(properties.jwt()))
                                .type(SignUpTokens.TYPE)
                                .build(),
                        JwtClaimsSet.builder()
                                .issuer(properties.jwt().issuer())
                                .subject("alice@example.com")
                                .issuedAt(now.minus(Duration.ofHours(2)))
                                .expiresAt(now.minus(Duration.ofHours(1)))
                                .build()))
                .getTokenValue();

        assertThat(tokens.verify(token.substring(0, token.length() - 2) + "xx")).isEmpty();
        assertThat(tokens.verify("not-a-token")).isEmpty();
        assertThat(tokens.verify(expired)).isEmpty();
    }

    @Test
    void accessTokensAndSignUpTokensCannotStandInForEachOther() {
        TokenService tokenService =
                new TokenService(config.jwtEncoder(keys), mock(TokenRevocationService.class), properties);
        String accessToken =
                tokenService.issue(1L, "alice", Role.USER, UUID.randomUUID()).value();

        assertThat(tokens.verify(accessToken)).isEmpty();
        assertThatThrownBy(() -> config.jwtDecoder(keys, properties, mock(TokenRevocationService.class))
                        .decode(tokens.issue("alice@example.com")))
                .isInstanceOf(JwtException.class);
    }
}
