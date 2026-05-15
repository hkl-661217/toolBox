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
}
