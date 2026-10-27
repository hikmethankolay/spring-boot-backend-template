package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Issues and checks the tokens in unsubscribe links: {@code v1.<base64url(userId.category)>.<base64url(HMAC)>}.
 *
 * <p>The tokens are stateless and never expire, so a link in an old email still works without logging in, as
 * mailbox providers expect, and using it twice does no harm. The user id is readable; the HMAC-SHA256 signature
 * keeps anyone from forging a token for another user. The key is derived from {@code app.mfa.encryption-key} with
 * HKDF-SHA256 (info {@code unsubscribe-v1}): that setting must never change, unlike the JWT keys, which are rotated
 * and would invalidate every link already sent.</p>
 */
@Component
public class UnsubscribeTokens {

    private static final String VERSION = "v1";

    private static final byte[] INFO = "unsubscribe-v1".getBytes(StandardCharsets.UTF_8);

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    /**
     * Derives the signing key.
     *
     * @param properties application properties holding {@code app.mfa.encryption-key}
     */
    public UnsubscribeTokens(AppProperties properties) {
        byte[] setting = properties.mfa().encryptionKey().getBytes(StandardCharsets.UTF_8);
        try {
            byte[] bytes = KDF.getInstance("HKDF-SHA256")
                    .deriveData(HKDFParameterSpec.ofExtract().addIKM(setting).thenExpand(INFO, 32));
            this.key = new SecretKeySpec(bytes, "HmacSHA256");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HKDF-SHA256 is not available", ex);
        }
    }

    /**
     * Issues the token of a user's unsubscribe link for a category.
     *
     * @param userId   the recipient
     * @param category the optional category the link turns off
     * @return the token
     */
    public String issue(long userId, MailCategory category) {
        byte[] payload = (userId + "." + category.key()).getBytes(StandardCharsets.UTF_8);
        return VERSION + "." + ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    /**
     * Checks a token.
     *
     * @param token the token from the link, may be {@code null}
     * @return the user and optional category it was issued for, or empty if it is malformed, forged or names a
     *     category that does not exist or cannot be turned off
     */
    public Optional<Claim> verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payload), DECODER.decode(parts[2]))) {
                return Optional.empty();
            }
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\.", 2);
            long userId = Long.parseLong(fields[0]);
            return MailCategory.fromKey(fields[1])
                    .filter(MailCategory::optional)
                    .map(category -> new Claim(userId, category));
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
            return Optional.empty();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }

    /**
     * What a valid token stands for.
     *
     * @param userId   the user who received the email
     * @param category the optional category to turn off
     */
    public record Claim(long userId, MailCategory category) {}
}
