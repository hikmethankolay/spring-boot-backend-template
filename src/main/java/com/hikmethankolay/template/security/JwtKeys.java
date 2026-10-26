package com.hikmethankolay.template.security;

import com.hikmethankolay.template.config.AppProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithm;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The JWT keys configured in {@code app.jwt}: the first key signs new tokens, every key verifies them.
 *
 * <p>Each key's id ({@code kid}) is its RFC 7638 thumbprint, so a token names the key that signed it.
 * Tokens without a {@code kid} (issued before key ids were added) are tried against every key.</p>
 */
public class JwtKeys {

    private final JWKSet keys;

    /**
     * Builds the keys from the configuration.
     *
     * @param properties application properties holding the algorithm and keys
     * @throws IllegalStateException if a key cannot be read
     */
    public JwtKeys(AppProperties properties) {
        AppProperties.Jwt jwt = properties.jwt();
        List<JWK> list = jwt.algorithm() == AppProperties.Jwt.Algorithm.RS256
                ? jwt.privateKey().stream().map(JwtKeys::rsaKey).toList()
                : jwt.secret().stream().map(JwtKeys::hmacKey).toList();
        this.keys = new JWKSet(list);
    }

    /**
     * The signing algorithm for the configuration.
     *
     * @param jwt the JWT settings
     * @return the algorithm to put in the token header
     */
    public static JwsAlgorithm algorithm(AppProperties.Jwt jwt) {
        return jwt.algorithm() == AppProperties.Jwt.Algorithm.RS256 ? SignatureAlgorithm.RS256 : MacAlgorithm.HS256;
    }

    /**
     * Signs with the first key only.
     *
     * @return the encoder
     */
    JwtEncoder encoder() {
        return new NimbusJwtEncoder(
                new ImmutableJWKSet<>(new JWKSet(keys.getKeys().getFirst())));
    }

    /**
     * Verifies with any of the keys; claims are checked by the validators set on the returned decoder.
     *
     * @return the decoder
     */
    NimbusJwtDecoder decoder() {
        JWSAlgorithm algorithm =
                JWSAlgorithm.parse(keys.getKeys().getFirst().getAlgorithm().getName());
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithm, new ImmutableJWKSet<>(keys)));
        // Spring Security's validators check the claims and the typ header (JwtTypeValidator), as with its own
        // decoder builders; Nimbus's own typ check would accept only "JWT", not access or sign-up tokens.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
        processor.setJWSTypeVerifier((type, context) -> {});
        return new NimbusJwtDecoder(processor);
    }

    /**
     * The public keys as a JWK Set; empty for HS256, whose secrets must never be published.
     *
     * @return the JSON object served at {@code /.well-known/jwks.json}
     */
    public Map<String, Object> publicJwks() {
        return keys.toPublicJWKSet().toJSONObject();
    }

    /**
     * Reads an HS256 secret.
     *
     * @param secret the configured secret
     * @return the key with its thumbprint as id
     */
    private static JWK hmacKey(String secret) {
        try {
            return new OctetSequenceKey.Builder(secret.getBytes(StandardCharsets.UTF_8))
                    .algorithm(JWSAlgorithm.HS256)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Invalid JWT_SECRET", ex);
        }
    }

    /**
     * Reads an RS256 private key and derives its public key.
     *
     * @param pem the private key in PEM format
     * @return the key pair with its thumbprint as id
     */
    private static JWK rsaKey(String pem) {
        PrivateKey key;
        try {
            PemContent content = PemContent.of(pem.strip());
            key = content == null ? null : content.getPrivateKey();
        } catch (IllegalStateException ex) {
            key = null;
        }
        if (!(key instanceof RSAPrivateCrtKey privateKey)) {
            throw new IllegalStateException("JWT_PRIVATE_KEY must contain an RSA private key in PEM format");
        }
        if (privateKey.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("JWT_PRIVATE_KEY must be at least 2048 bits");
        }
        try {
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyIDFromThumbprint()
                    .build();
        } catch (GeneralSecurityException | JOSEException ex) {
            throw new IllegalStateException("Invalid JWT_PRIVATE_KEY", ex);
        }
    }
}
