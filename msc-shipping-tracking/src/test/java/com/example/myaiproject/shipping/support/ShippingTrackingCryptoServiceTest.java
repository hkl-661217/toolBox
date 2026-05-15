package com.example.myaiproject.shipping.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShippingTrackingCryptoServiceTest {

    private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void encryptThenDecryptRecoversOriginal() {
        ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService(TEST_KEY);

        String plaintext = "qq-auth-code-abc123";
        String stored = crypto.encrypt(plaintext);

        assertTrue(stored.startsWith("v1:"), "encrypted value should be versioned");
        assertNotEquals(plaintext, stored, "ciphertext must differ from plaintext");
        assertEquals(plaintext, crypto.decryptIfNeeded(stored));
    }

    @Test
    void encryptProducesDifferentCiphertextForSamePlaintext() {
        ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService(TEST_KEY);

        String a = crypto.encrypt("same-secret");
        String b = crypto.encrypt("same-secret");

        assertNotEquals(a, b);
        assertEquals("same-secret", crypto.decryptIfNeeded(a));
        assertEquals("same-secret", crypto.decryptIfNeeded(b));
    }

    @Test
    void decryptIfNeededReturnsLegacyPlaintextAsIs() {
        ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService(TEST_KEY);
        assertEquals("old-plain-value", crypto.decryptIfNeeded("old-plain-value"));
    }

    @Test
    void decryptIfNeededRejectsTamperedCiphertext() {
        ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService(TEST_KEY);

        String stored = crypto.encrypt("real-secret");
        char[] chars = stored.toCharArray();
        chars[chars.length - 1] = (chars[chars.length - 1] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> crypto.decryptIfNeeded(tampered));
        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().toLowerCase().contains("decrypt")
                        || error.getMessage().toLowerCase().contains("malformed"),
                "Error must point at decryption/format issue; actual: " + error.getMessage());
    }
}
