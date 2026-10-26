package com.hikmethankolay.template.security;

import com.hikmethankolay.template.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * whoever completes the sign-up can read that inbox. Access tokens and sign-up tokens are told apart by
 * {@code typ}, so neither can be used as the other.
 *
 * <p>A used token's {@code jti} is kept in Redis until the token expires, so a link cannot create a second account
 * after the first was deleted or moved to another address. If Redis is unavailable that check is skipped: the
 * address can still have only one account.</p>
 */
@Component
public class SignUpTokens {

    private static final Logger log = LoggerFactory.getLogger(SignUpTokens.class);

    /** The {@code typ} header of sign-up tokens. */
    static final String TYPE = "sign-up+jwt";

    private final JwtEncoder encoder;

    private final NimbusJwtDecoder decoder;

    private final AppProperties.Jwt jwt;

    private final Duration ttl;

    private final StringRedisTemplate redis;

    private final String usedKeyPrefix;

    /**
     * Creates the component.
     *
     * @param encoder         signs the tokens with the application's JWT keys
     * @param keys            the keys that verify them
     * @param properties      issuer, algorithm and link lifetime
     * @param redis           remembers used tokens
     * @param applicationName prefix of the Redis keys
     */
    SignUpTokens(
            JwtEncoder encoder,
            JwtKeys keys,
            AppProperties properties,
            StringRedisTemplate redis,
            @Value("${spring.application.name}") String applicationName) {
        this.encoder = encoder;
        this.redis = redis;
        this.usedKeyPrefix = applicationName + ":sign-up-used:";
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
                .id(UUID.randomUUID().toString())
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
     * @return the address, or empty if the token is forged, expired, used or not a sign-up token
     */
    public Optional<String> verify(String token) {
        return decode(token).filter(link -> !isUsed(link)).map(Jwt::getSubject);
    }

    /**
     * Records that a token created an account, until it expires.
     *
     * @param token a token {@link #verify} accepted
     */
    public void markUsed(String token) {
        decode(token).ifPresent(link -> {
            try {
                redis.opsForValue()
                        .set(usedKeyPrefix + link.getId(), "1", Duration.between(Instant.now(), link.getExpiresAt()));
            } catch (DataAccessException ex) {
                log.warn(
                        "Could not record a used sign-up link: {}",
                        ex.getClass().getSimpleName());
            }
        });
    }

    private Optional<Jwt> decode(String token) {
        try {
            return Optional.of(decoder.decode(token));
        } catch (JwtException ex) {
            return Optional.empty();
        }
    }

    private boolean isUsed(Jwt link) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(usedKeyPrefix + link.getId()));
        } catch (DataAccessException ex) {
            log.warn(
                    "Could not check for a used sign-up link: {}", ex.getClass().getSimpleName());
            return false;
        }
    }
}
