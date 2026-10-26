package com.hikmethankolay.template.security;

import com.hikmethankolay.template.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * The token in a sign-up link: a JWT of its own type ({@code sign-up+jwt}) whose subject is the email address,
 * signed with the application's JWT keys and valid for {@code app.account.verification-token-ttl}. It proves that
 * whoever completes the sign-up can read that inbox. Nothing is stored: a link opened twice fails because the
 * address then has an account. Access tokens and sign-up tokens are told apart by {@code typ}, so neither can be
 * used as the other.
 */
@Component
public class SignUpTokens {

    /** The {@code typ} header of sign-up tokens. */
    static final String TYPE = "sign-up+jwt";

    private final JwtEncoder encoder;

    private final NimbusJwtDecoder decoder;

    private final AppProperties.Jwt jwt;

    private final Duration ttl;

    /**
     * Creates the component.
     *
     * @param encoder    signs the tokens with the application's JWT keys
     * @param keys       the keys that verify them
     * @param properties issuer, algorithm and link lifetime
     */
    SignUpTokens(JwtEncoder encoder, JwtKeys keys, AppProperties properties) {
        this.encoder = encoder;
        this.jwt = properties.jwt();
        this.ttl = properties.account().verificationTokenTtl();
        this.decoder = keys.decoder();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new JwtIssuerValidator(jwt.issuer()), new JwtTypeValidator(TYPE)));
    }

    /**
     * Issues the token for a sign-up link.
     *
     * @param email the normalised address the link is sent to
     * @return the signed token
     */
    public String issue(String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(email)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(JwtKeys.algorithm(jwt)).type(TYPE).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Reads the address from a sign-up token.
     *
     * @param token the token from the link
     * @return the address, or empty if the token is forged, expired or not a sign-up token
     */
    public Optional<String> verify(String token) {
        try {
            return Optional.ofNullable(decoder.decode(token).getSubject());
        } catch (JwtException ex) {
            return Optional.empty();
        }
    }
}
