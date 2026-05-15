package com.example.myaiproject.shipping.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM-256 encrypt/decrypt for SMTP credentials. Stored values are wrapped as
 * {@code v1:<base64(iv)>:<base64(ciphertext+tag)>} — the version prefix lets future
 * code distinguish encrypted values from legacy plaintext rows.
 */
public class ShippingTrackingCryptoService {
    static final String VERSION_PREFIX = "v1:";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public ShippingTrackingCryptoService(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "shipping.tracking.encryption.key (env SHIPPING_TRACKING_ENCRYPTION_KEY) must be set "
                    + "to a base64-encoded 32-byte AES key.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "shipping.tracking.encryption.key is not valid base64.", error);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "shipping.tracking.encryption.key must decode to 32 bytes (was "
                    + keyBytes.length + ").");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(cipherText);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Failed to encrypt notification credential.", error);
        }
    }

    public String decryptIfNeeded(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(VERSION_PREFIX)) {
            return stored;
        }
        String body = stored.substring(VERSION_PREFIX.length());
        int colon = body.indexOf(':');
        if (colon <= 0 || colon == body.length() - 1) {
            throw new IllegalStateException("Malformed v1 ciphertext (missing iv:ct separator).");
        }
        byte[] iv = Base64.getDecoder().decode(body.substring(0, colon));
        byte[] cipherText = Base64.getDecoder().decode(body.substring(colon + 1));
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Failed to decrypt notification credential.", error);
        }
    }
}
