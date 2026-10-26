package com.hikmethankolay.template.mfa;

import com.hikmethankolay.template.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts TOTP secrets with AES-256-GCM, so a copy of the database alone does not reveal them.
 *
 * <p>The key is derived from {@code app.mfa.encryption-key} with HKDF-SHA256 (RFC 5869; info
 * {@code mfa-secret-v1}). Each value is {@code v1:} followed by {@code base64(iv || ciphertext || tag)} with a random
 * 96-bit IV, and the user id is authenticated data: a secret copied to another user's row does not decrypt. The
 * version prefix lets a later key or scheme coexist with this one; values without it are from before (key: the
 * SHA-256 of the setting) and still decrypt.</p>
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Prefix of values encrypted with the HKDF-derived key. */
    static final String V1 = "v1:";

    private static final byte[] V1_INFO = "mfa-secret-v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKey key;

    private final SecretKey legacyKey;

    /**
     * Creates the cipher.
     *
     * @param properties application properties holding the encryption key
     */
    public SecretCipher(AppProperties properties) {
        byte[] setting = properties.mfa().encryptionKey().getBytes(StandardCharsets.UTF_8);
        this.key = hkdf(setting);
        this.legacyKey = new SecretKeySpec(sha256(properties.mfa().encryptionKey()), "AES");
    }

    /**
     * Encrypts a secret.
     *
     * @param plaintext the secret
     * @param userId    the owner, bound to the ciphertext
     * @return the encrypted value, Base64
     */
    public String encrypt(byte[] plaintext, long userId) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return V1
                    + Base64.getEncoder()
                            .encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length)
                                    .put(iv)
                                    .put(ciphertext)
                                    .array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt the TOTP secret", ex);
        }
    }

    /**
     * Decrypts a secret.
     *
     * @param encrypted the value returned by {@link #encrypt(byte[], long)}
     * @param userId    the owner
     * @return the secret
     * @throws IllegalStateException if the value was encrypted with another key or for another user
     */
    public byte[] decrypt(String encrypted, long userId) {
        try {
            boolean v1 = encrypted.startsWith(V1);
            byte[] bytes = Base64.getDecoder().decode(v1 ? encrypted.substring(V1.length()) : encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, v1 ? key : legacyKey, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            cipher.updateAAD(aad(userId));
            return cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Could not decrypt the TOTP secret; was MFA_ENCRYPTION_KEY changed?", ex);
        }
    }

    private static byte[] aad(long userId) {
        return ByteBuffer.allocate(Long.BYTES).putLong(userId).array();
    }

    /**
     * Derives the AES-256 key from the setting with HKDF-SHA256 (no salt, info {@code mfa-secret-v1}).
     *
     * @param setting the bytes of {@code app.mfa.encryption-key}
     * @return the key
     */
    private static SecretKey hkdf(byte[] setting) {
        try {
            return KDF.getInstance("HKDF-SHA256")
                    .deriveKey(
                            "AES", HKDFParameterSpec.ofExtract().addIKM(setting).thenExpand(V1_INFO, 32));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HKDF-SHA256 is not available", ex);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
