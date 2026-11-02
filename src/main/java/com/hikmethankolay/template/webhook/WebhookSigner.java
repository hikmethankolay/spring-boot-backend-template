package com.hikmethankolay.template.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signing secrets and signatures of the <a href="https://www.standardwebhooks.com">Standard Webhooks</a> spec (v1,
 * HMAC-SHA256), so receivers can verify deliveries with its libraries in most languages.
 *
 * <p>A secret is {@code whsec_} followed by 32 random bytes in Base64. A signature covers
 * {@code <webhook-id>.<webhook-timestamp>.<body>} and is sent as {@code v1,<base64>}; during a secret rotation the
 * {@code webhook-signature} header carries one signature per secret, separated by spaces.</p>
 */
public final class WebhookSigner {

    /** Prefix of every signing secret. */
    public static final String SECRET_PREFIX = "whsec_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Not instantiable. */
    private WebhookSigner() {}

    /**
     * Generates a signing secret.
     *
     * @return {@code whsec_} and 32 random bytes in Base64
     */
    public static String newSecret() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return SECRET_PREFIX + Base64.getEncoder().encodeToString(key);
    }

    /**
     * Builds the {@code webhook-signature} header value.
     *
     * @param secrets   the secrets to sign with: the current one, and the previous one during a rotation
     * @param messageId the {@code webhook-id}
     * @param timestamp the {@code webhook-timestamp}, in seconds since the epoch
     * @param body      the exact request body
     * @return space-separated {@code v1,<signature>} values
     */
    public static String signatureHeader(List<String> secrets, String messageId, long timestamp, String body) {
        return secrets.stream()
                .map(secret -> "v1," + sign(secret, messageId, timestamp, body))
                .collect(Collectors.joining(" "));
    }

    /**
     * Signs a message with one secret.
     *
     * @param secret    a {@code whsec_} secret
     * @param messageId the {@code webhook-id}
     * @param timestamp the {@code webhook-timestamp}, in seconds
     * @param body      the exact request body
     * @return the Base64 HMAC-SHA256
     */
    static String sign(String secret, String messageId, long timestamp, String body) {
        byte[] key = Base64.getDecoder()
                .decode(secret.startsWith(SECRET_PREFIX) ? secret.substring(SECRET_PREFIX.length()) : secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signature = mac.doFinal((messageId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }
}
