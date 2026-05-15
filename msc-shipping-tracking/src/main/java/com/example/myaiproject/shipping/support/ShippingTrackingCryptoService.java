package com.example.myaiproject.shipping.support;

import com.example.myaiproject.shipping.service.ShippingTrackingProperties;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * AES-GCM-256 encrypt/decrypt for SMTP credentials. Stored values are wrapped as
 * {@code v1:<base64(iv)>:<base64(ciphertext+tag)>} — the version prefix lets future
 * code distinguish encrypted values from legacy plaintext rows.
 */
@Service
public class ShippingTrackingCryptoService {
    static final String VERSION_PREFIX = "v1:";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private final String rawKey;
    private final JdbcTemplate jdbcTemplate;
    private final SecretKeySpec keySpec; // null when key is not configured
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ShippingTrackingCryptoService(ShippingTrackingProperties properties, JdbcTemplate jdbcTemplate) {
        this(properties.getEncryptionKey(), jdbcTemplate);
    }

    // Package-private for unit tests that don't want a JdbcTemplate.
    ShippingTrackingCryptoService(String base64Key) {
        this(base64Key, null);
    }

    ShippingTrackingCryptoService(String base64Key, JdbcTemplate jdbcTemplate) {
        this.rawKey = base64Key == null ? "" : base64Key;
        this.jdbcTemplate = jdbcTemplate;
        this.keySpec = this.rawKey.isBlank() ? null : buildKeySpec(this.rawKey);
    }

    @PostConstruct
    void verifyKeyAvailableIfEncryptedRowsExist() {
        if (jdbcTemplate == null) {
            return;
        }
        Integer encryptedCount = jdbcTemplate.queryForObject(
                "select count(*) from shipping_tracking_notification_account where smtp_password like 'v1:%'",
                Integer.class);
        if (encryptedCount != null && encryptedCount > 0 && keySpec == null) {
            throw new IllegalStateException(
                    "shipping_tracking_notification_account contains " + encryptedCount
                    + " encrypted row(s) but shipping.tracking.encryption.key is not set. "
                    + "Refusing to start to avoid silently treating ciphertext as plaintext.");
        }
    }

    private static SecretKeySpec buildKeySpec(String base64Key) {
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
        return new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        if (keySpec == null) {
            throw new IllegalStateException(
                    "shipping.tracking.encryption.key is not set; cannot encrypt new credentials.");
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
        if (keySpec == null) {
            throw new IllegalStateException(
                    "shipping.tracking.encryption.key is not set; cannot decrypt 'v1:' value.");
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
