package dev.finguard.llm;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryptor for API keys stored in the database.
 *
 * <p>Each call to {@link #encrypt} generates a fresh 96-bit (12-byte) random IV so that
 * encrypting the same plaintext twice produces different ciphertexts. The 128-bit GCM
 * authentication tag detects any in-database tampering.</p>
 *
 * <p>Wire format stored in the database:
 * {@code Base64( IV[12 bytes] || AES-GCM-ciphertext+tag )}</p>
 *
 * <p>The 256-bit (32-byte) key is read from {@code finguard.encryption.key} (Base64-encoded).
 * The application refuses to start if the key is missing or the wrong length.</p>
 *
 * <p>Raw key bytes are zeroed from the heap immediately after the {@link SecretKey} is built.</p>
 */
@Component
public class ApiKeyEncryptor {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyEncryptor.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES     = 12;   // NIST SP 800-38D recommended for GCM
    private static final int TAG_BITS     = 128;  // maximum GCM authentication-tag length
    private static final int KEY_BYTES    = 32;   // 256-bit AES key

    private final SecretKey   secretKey;
    private final SecureRandom secureRandom;

    public ApiKeyEncryptor(@Value("${finguard.encryption.key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "finguard.encryption.key must decode to exactly " + KEY_BYTES +
                    " bytes (256-bit AES key). Got " + keyBytes.length + " bytes. " +
                    "Generate a valid key: openssl rand -base64 32");
        }
        this.secretKey    = new SecretKeySpec(keyBytes, "AES");
        this.secureRandom = new SecureRandom();
        Arrays.fill(keyBytes, (byte) 0); // zero raw key bytes from the heap immediately
    }

    @PostConstruct
    void logStartup() {
        log.info("ApiKeyEncryptor ready (AES-256-GCM)");
    }

    /**
     * Encrypts a plaintext API key.
     *
     * @param plaintext the raw API key; {@code null} is returned as {@code null}
     * @return Base64-encoded {@code IV || ciphertext+tag}
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv,         0, combined, 0,       IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_BYTES, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt API key", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}.
     *
     * @param ciphertext Base64-encoded {@code IV || ciphertext+tag}; {@code null} returns {@code null}
     * @return the original plaintext API key
     * @throws IllegalStateException if GCM authentication fails (tampered or wrong key)
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            byte[] combined  = Base64.getDecoder().decode(ciphertext);
            byte[] iv        = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(combined, IV_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to decrypt API key — value may be corrupted " +
                    "or was encrypted with a different key", e);
        }
    }
}
