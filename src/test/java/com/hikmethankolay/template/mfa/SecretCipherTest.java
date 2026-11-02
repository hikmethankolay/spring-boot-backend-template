package com.hikmethankolay.template.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hikmethankolay.template.TestProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String OTHER_KEY = "another-key-that-is-at-least-32-characters";

    private final SecretCipher cipher = new SecretCipher(TestProperties.create());

    private static SecretCipher cipher(String... keys) {
        return new SecretCipher(TestProperties.withMfaKeys(keys));
    }

    @Test
    void decryptsWhatItEncrypted() {
        byte[] secret = Totp.generateSecret();

        String encrypted = cipher.encrypt(secret, 42);

        assertThat(cipher.decrypt(encrypted, 42)).isEqualTo(secret);
        // A random IV per value: the same secret never encrypts to the same text.
        assertThat(cipher.encrypt(secret, 42)).isNotEqualTo(encrypted);
    }

    @Test
    void aSecretCopiedToAnotherUserDoesNotDecrypt() {
        String encrypted = cipher.encrypt(Totp.generateSecret(), 42);

        assertThatThrownBy(() -> cipher.decrypt(encrypted, 43)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyCannotDecrypt() {
        SecretCipher otherKey = cipher(OTHER_KEY);
        String encrypted = cipher.encrypt(Totp.generateSecret(), 42);

        assertThatThrownBy(() -> otherKey.decrypt(encrypted, 42)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void afterARotationOldValuesStillDecryptAndAreMarkedForReencryption() {
        byte[] secret = Totp.generateSecret();
        String old = cipher.encrypt(secret, 42);
        SecretCipher rotated = cipher(OTHER_KEY, TestProperties.MFA_KEY);

        assertThat(cipher.isCurrent(old, 42)).isTrue();
        assertThat(rotated.decrypt(old, 42)).isEqualTo(secret);
        assertThat(rotated.isCurrent(old, 42)).isFalse();

        String reencrypted = rotated.encrypt(secret, 42);
        assertThat(rotated.isCurrent(reencrypted, 42)).isTrue();
        // Once every value is moved, the old key can go.
        assertThat(cipher(OTHER_KEY).decrypt(reencrypted, 42)).isEqualTo(secret);
        assertThatThrownBy(() -> cipher(OTHER_KEY).decrypt(old, 42)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aValueForAnotherUserIsNotCurrentEither() {
        assertThat(cipher.isCurrent(cipher.encrypt(Totp.generateSecret(), 42), 43))
                .isFalse();
    }

    @Test
    void newValuesAreVersionedAndUseTheDerivedKey() {
        String encrypted = cipher.encrypt("totp-secret".getBytes(StandardCharsets.UTF_8), 7L);

        assertThat(encrypted).startsWith(SecretCipher.V1);
        // Without the prefix it is read as an old value, with the old key, which is not the derived one.
        assertThatThrownBy(() -> cipher.decrypt(encrypted.substring(SecretCipher.V1.length()), 7L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void valuesFromBeforeTheKeyDerivationStillDecrypt() throws Exception {
        String legacy = legacyValue(TestProperties.MFA_KEY, "totp-secret".getBytes(StandardCharsets.UTF_8), 7L);

        assertThat(cipher.decrypt(legacy, 7L)).asString(StandardCharsets.UTF_8).isEqualTo("totp-secret");
        assertThatThrownBy(() -> cipher.decrypt(legacy, 8L)).isInstanceOf(IllegalStateException.class);
        // Values in the old format are moved to the current one on use, also when their key is not the first.
        assertThat(cipher.isCurrent(legacy, 7L)).isFalse();
        assertThat(cipher(OTHER_KEY, TestProperties.MFA_KEY).decrypt(legacy, 7L))
                .asString(StandardCharsets.UTF_8)
                .isEqualTo("totp-secret");
    }

    /** Encrypts a secret in the earlier format: no prefix, key = SHA-256 of the setting. */
    static String legacyValue(String setting, byte[] secret, long userId) throws Exception {
        byte[] legacyKey = MessageDigest.getInstance("SHA-256").digest(setting.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(legacyKey, "AES"), new GCMParameterSpec(128, iv));
        aes.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(userId).array());
        byte[] ciphertext = aes.doFinal(secret);
        return Base64.getEncoder()
                .encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length)
                        .put(iv)
                        .put(ciphertext)
                        .array());
    }
}
