package com.hikmethankolay.template.webhook;

import com.hikmethankolay.template.mfa.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Encrypts the endpoints' signing secrets with {@code WEBHOOK_ENCRYPTION_KEY} (AES-256-GCM, owner id as authenticated
 * data). The key derivation uses its own HKDF info, so a webhook value never decrypts as a TOTP secret or the other way
 * round, even if both settings held the same key.
 */
@Component
public class WebhookSecrets {

    private final SecretCipher cipher;

    /**
     * Creates the cipher from {@code app.webhooks.encryption-key}.
     *
     * @param properties the webhook settings
     */
    public WebhookSecrets(WebhookProperties properties) {
        this.cipher = new SecretCipher(properties.encryptionKey(), "webhook-secret-v1", "WEBHOOK_ENCRYPTION_KEY");
    }

    /**
     * Encrypts a signing secret with the first key.
     *
     * @param secret the {@code whsec_} secret
     * @param userId the endpoint's owner
     * @return the value to store
     */
    public String encrypt(String secret, long userId) {
        return cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8), userId);
    }

    /**
     * Decrypts a stored signing secret.
     *
     * @param encrypted the stored value
     * @param userId    the endpoint's owner
     * @return the secret, or empty if no configured key decrypts it (its key was removed from the setting)
     */
    public Optional<String> decrypt(String encrypted, long userId) {
        try {
            return Optional.of(new String(cipher.decrypt(encrypted, userId), StandardCharsets.UTF_8));
        } catch (IllegalStateException ex) {
            return Optional.empty();
        }
    }
}
