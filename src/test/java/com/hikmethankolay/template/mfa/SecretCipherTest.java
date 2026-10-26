package com.hikmethankolay.template.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher(TestProperties.create());

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
        AppProperties p = TestProperties.create();
        SecretCipher otherKey = new SecretCipher(new AppProperties(
                p.jwt(),
                p.cookie(),
                p.cors(),
                p.login(),
                p.rateLimit(),
                p.account(),
                p.mail(),
                p.admin(),
                new AppProperties.Mfa("another-key-that-is-at-least-32-characters", Duration.ofMinutes(5)),
                p.socialLogin()));
        String encrypted = cipher.encrypt(Totp.generateSecret(), 42);

        assertThatThrownBy(() -> otherKey.decrypt(encrypted, 42)).isInstanceOf(IllegalStateException.class);
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
        // The earlier format: no prefix, key = SHA-256 of the setting.
        byte[] legacyKey = MessageDigest.getInstance("SHA-256")
                .digest(TestProperties.create().mfa().encryptionKey().getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(legacyKey, "AES"), new GCMParameterSpec(128, iv));
        aes.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(7L).array());
        byte[] ciphertext = aes.doFinal("totp-secret".getBytes(StandardCharsets.UTF_8));
        String legacy = Base64.getEncoder()
                .encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length)
                        .put(iv)
                        .put(ciphertext)
                        .array());

        assertThat(cipher.decrypt(legacy, 7L)).asString(StandardCharsets.UTF_8).isEqualTo("totp-secret");
        assertThatThrownBy(() -> cipher.decrypt(legacy, 8L)).isInstanceOf(IllegalStateException.class);
    }
}
