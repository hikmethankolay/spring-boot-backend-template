package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.user.Role;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class TokenServiceTest {

    private static final String OTHER_SECRET = "another-secret-that-is-also-32-chars-long";
    private static final UUID SESSION = UUID.randomUUID();

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final AppProperties properties = TestProperties.create();
    private final TokenRevocationService revocations = mock(TokenRevocationService.class);
    private final TokenService tokenService = tokenService(properties);
    private final JwtDecoder decoder = decoder(properties);

    @Test
    void issuedTokenCarriesUserClaims() {
        TokenService.IssuedToken token = tokenService.issue(42L, "alice", Role.ADMIN, SESSION);

        Jwt jwt = decoder.decode(token.value());
        assertThat(jwt.getId()).isNotBlank();
        assertThat(TokenService.userId(jwt)).isEqualTo(42L);
        assertThat(jwt.getClaimAsString(TokenService.USERNAME_CLAIM)).isEqualTo("alice");
        assertThat(jwt.getClaimAsStringList(TokenService.ROLES_CLAIM)).isEqualTo(List.of("ADMIN"));
        assertThat(TokenService.sessionId(jwt)).isEqualTo(SESSION);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(TestProperties.ISSUER);
        assertThat(token.ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        AppProperties other =
                TestProperties.withJwt(TestProperties.hs256(List.of(OTHER_SECRET), TestProperties.ISSUER));
        String foreignToken =
                tokenService(other).issue(1L, "mallory", Role.ADMIN, SESSION).value();

        assertThatThrownBy(() -> decoder.decode(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void revokedTokenIsRejected() {
        String value = tokenService.issue(1L, "bob", Role.USER, SESSION).value();
        String tokenId = decoder.decode(value).getId();
        when(revocations.isRevoked(argThat((Jwt jwt) -> tokenId.equals(jwt.getId()))))
                .thenReturn(true);

        assertThatThrownBy(() -> decoder.decode(value))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void tokenIssuedRightAfterARevocationCutOffStartsAtTheCutOff() {
        Instant cutOff = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        when(revocations.issuedNotBefore(7L)).thenReturn(Optional.of(cutOff));

        Jwt jwt = decoder.decode(
                tokenService.issue(7L, "carol", Role.USER, SESSION).value());

        assertThat(jwt.getIssuedAt()).isEqualTo(cutOff);
    }

    @Test
    void everyTokenGetsItsOwnId() {
        String first = decoder.decode(
                        tokenService.issue(1L, "bob", Role.USER, SESSION).value())
                .getId();
        String second = decoder.decode(
                        tokenService.issue(1L, "bob", Role.USER, SESSION).value())
                .getId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        AppProperties other =
                TestProperties.withJwt(TestProperties.hs256(List.of(TestProperties.JWT_SECRET), "someone-else"));
        String foreignToken =
                tokenService(other).issue(1L, "mallory", Role.ADMIN, SESSION).value();

        assertThatThrownBy(() -> decoder.decode(foreignToken)).isInstanceOf(JwtException.class);
    }

    /** After a rotation, tokens signed with the previous secret are still accepted, and new ones use the new secret. */
    @Test
    void tokenSignedWithAPreviousSecretIsAcceptedAfterRotation() {
        String oldToken = tokenService.issue(1L, "bob", Role.USER, SESSION).value();
        AppProperties rotated = TestProperties.withJwt(
                TestProperties.hs256(List.of(OTHER_SECRET, TestProperties.JWT_SECRET), TestProperties.ISSUER));

        assertThat(TokenService.userId(decoder(rotated).decode(oldToken))).isEqualTo(1L);
        // New tokens are signed with the first secret, which the old configuration does not know.
        String newToken =
                tokenService(rotated).issue(1L, "bob", Role.USER, SESSION).value();
        assertThatThrownBy(() -> decoder.decode(newToken)).isInstanceOf(JwtException.class);
    }

    /** Tokens carry the id of the key that signed them. */
    @Test
    void tokensNameTheirSigningKey() {
        Jwt jwt =
                decoder.decode(tokenService.issue(1L, "bob", Role.USER, SESSION).value());

        assertThat(jwt.getHeaders()).containsKey("kid").containsEntry("alg", "HS256");
    }

    /** HS256 secrets never appear in the JWK Set. */
    @Test
    void hs256SecretsAreNeverPublished() {
        assertThat(new JwtKeys(properties).publicJwks()).isEqualTo(Map.of("keys", List.of()));
    }

    /**
     * RS256 tokens verify with the published public key alone.
     *
     * @throws Exception if the test key cannot be generated
     */
    @Test
    void rs256TokensVerifyWithThePublishedPublicKey() throws Exception {
        AppProperties rs256 = TestProperties.withJwt(new AppProperties.Jwt(
                AppProperties.Jwt.Algorithm.RS256,
                List.of(),
                List.of(rsaPrivateKeyPem(2048)),
                TestProperties.ISSUER,
                Duration.ofMinutes(15),
                Duration.ofDays(1),
                Duration.ofDays(30)));
        String token = tokenService(rs256).issue(9L, "dave", Role.USER, SESSION).value();

        JWKSet published = JWKSet.parse(new JwtKeys(rs256).publicJwks());
        assertThat(published.getKeys())
                .singleElement()
                .satisfies(key -> assertThat(key.isPrivate()).isFalse());
        RSAPublicKey publicKey = published.getKeys().getFirst().toRSAKey().toRSAPublicKey();
        Jwt jwt = NimbusJwtDecoder.withPublicKey(publicKey).build().decode(token);
        assertThat(TokenService.userId(jwt)).isEqualTo(9L);
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
    }

    /**
     * An RSA key shorter than 2048 bits stops the application.
     *
     * @throws Exception if the test key cannot be generated
     */
    @Test
    void shortRsaKeyFailsAtStartup() throws Exception {
        AppProperties weak = TestProperties.withJwt(new AppProperties.Jwt(
                AppProperties.Jwt.Algorithm.RS256,
                List.of(),
                List.of(rsaPrivateKeyPem(1024)),
                TestProperties.ISSUER,
                Duration.ofMinutes(15),
                Duration.ofDays(1),
                Duration.ofDays(30)));

        assertThatThrownBy(() -> new JwtKeys(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048 bits");
    }

    /** Tokens issued before sessions were listed have no {@code sid} claim and no session id. */
    @Test
    void tokenWithoutSessionClaimHasNoSessionId() {
        Jwt jwt =
                Jwt.withTokenValue("token").header("alg", "HS256").subject("1").build();

        assertThat(TokenService.sessionId(jwt)).isNull();
    }

    /** A value that is not an RSA private key stops the application with a message naming the setting. */
    @Test
    void invalidPrivateKeyFailsAtStartup() {
        AppProperties broken = TestProperties.withJwt(new AppProperties.Jwt(
                AppProperties.Jwt.Algorithm.RS256,
                List.of(),
                List.of("not a key"),
                TestProperties.ISSUER,
                Duration.ofMinutes(15),
                Duration.ofDays(1),
                Duration.ofDays(30)));

        assertThatThrownBy(() -> new JwtKeys(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    /**
     * Creates a token service for the given settings.
     *
     * @param props the settings
     * @return the service
     */
    private TokenService tokenService(AppProperties props) {
        return new TokenService(securityConfig.jwtEncoder(new JwtKeys(props)), revocations, props);
    }

    /**
     * Creates the application's decoder for the given settings.
     *
     * @param props the settings
     * @return the decoder
     */
    private JwtDecoder decoder(AppProperties props) {
        return securityConfig.jwtDecoder(new JwtKeys(props), props, revocations);
    }

    /**
     * Generates an RSA private key in PKCS#8 PEM format.
     *
     * @param bits key size
     * @return the PEM text
     * @throws Exception if no RSA key generator is available
     */
    private static String rsaPrivateKeyPem(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        String body = Base64.getMimeEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }
}
