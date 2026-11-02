package com.hikmethankolay.template.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.mfa.SecretCipher;
import com.hikmethankolay.template.user.Role;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Webhook secrets have their own key, separate from the TOTP secrets', and it is checked at startup. */
class WebhookSecretsTest {

    private static final String KEY = "webhook-key-abcdefghijklmnopqrstuvwxyz-0123456789";

    private static final String OTHER_KEY = "another-key-ABCDEFGHIJKLMNOPQRSTUVWXYZ-9876543210";

    @Test
    void aSecretDecryptsOnlyForItsOwner() {
        WebhookSecrets secrets = secrets(List.of(KEY));

        String stored = secrets.encrypt("whsec_abc", 7L);

        assertThat(secrets.decrypt(stored, 7L)).contains("whsec_abc");
        assertThat(secrets.decrypt(stored, 8L)).isEmpty();
    }

    @Test
    void aRotatedKeyListStillDecryptsOldValues() {
        String stored = secrets(List.of(OTHER_KEY)).encrypt("whsec_abc", 7L);

        assertThat(secrets(List.of(KEY, OTHER_KEY)).decrypt(stored, 7L)).contains("whsec_abc");
        assertThat(secrets(List.of(KEY)).decrypt(stored, 7L))
                .as("its key was dropped")
                .isEmpty();
    }

    @Test
    void aTotpValueNeverDecryptsAsAWebhookSecretEvenUnderTheSameKey() {
        String totp = new SecretCipher(List.of(KEY), "mfa-secret-v1", "MFA_ENCRYPTION_KEY")
                .encrypt("whsec_abc".getBytes(StandardCharsets.UTF_8), 7L);

        assertThat(secrets(List.of(KEY)).decrypt(totp, 7L)).isEmpty();
    }

    @Test
    void theKeyIsRequiredOnlyWhileWebhooksAreEnabledAndMustBeStrong() {
        assertThat(properties(true, List.of()).isEncryptionKeySet()).isFalse();
        assertThat(properties(false, List.of()).isEncryptionKeySet()).isTrue();
        assertThat(properties(true, List.of(KEY)).isEncryptionKeySet()).isTrue();
        assertThat(properties(true, List.of(KEY)).isEncryptionKeyStrong()).isTrue();
        assertThat(properties(true, List.of("too-short")).isEncryptionKeyStrong())
                .isFalse();
        assertThat(properties(true, List.of("a".repeat(40))).isEncryptionKeyStrong())
                .isFalse();
        assertThat(properties(true, null).encryptionKey()).isEmpty();
    }

    private static WebhookSecrets secrets(List<String> keys) {
        return new WebhookSecrets(properties(true, keys));
    }

    private static WebhookProperties properties(boolean enabled, List<String> keys) {
        return new WebhookProperties(
                enabled,
                keys,
                Role.ADMIN,
                false,
                false,
                10,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                8,
                24,
                Duration.ofSeconds(5),
                Duration.ofDays(7));
    }
}
