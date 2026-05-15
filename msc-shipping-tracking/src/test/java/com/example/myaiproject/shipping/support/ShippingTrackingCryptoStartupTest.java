package com.example.myaiproject.shipping.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ShippingTrackingCryptoStartupTest {

    private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void startupFailsWhenEncryptedRowsExistButKeyMissing() {
        var db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(db);
            jdbc.update("""
                    insert into shipping_tracking_notification_account
                        (email, smtp_password, enabled, created_at, updated_at)
                    values ('seed@example.com', 'v1:abc:def', true,
                            current_timestamp, current_timestamp)
                    """);

            ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService("", jdbc);

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    crypto::verifyKeyAvailableIfEncryptedRowsExist);
            assertTrue(error.getMessage().contains("encryption.key"),
                    "error must point users at the missing property; got: " + error.getMessage());
        } finally {
            db.shutdown();
        }
    }

    @Test
    void startupPassesWhenNoEncryptedRowsAndKeyMissing() {
        var db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(db);
            ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService("", jdbc);

            crypto.verifyKeyAvailableIfEncryptedRowsExist();
        } finally {
            db.shutdown();
        }
    }

    @Test
    void startupEncryptsLegacyPlaintextRowsWhenKeyPresent() {
        var db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(db);
            jdbc.update("""
                    insert into shipping_tracking_notification_account
                        (email, smtp_password, enabled, created_at, updated_at)
                    values ('legacy@example.com', 'plain-secret', true,
                            current_timestamp, current_timestamp)
                    """);

            ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService(TEST_KEY, jdbc);
            crypto.verifyKeyAvailableIfEncryptedRowsExist();

            String stored = jdbc.queryForObject(
                    "select smtp_password from shipping_tracking_notification_account where email = ?",
                    String.class,
                    "legacy@example.com");
            assertTrue(stored.startsWith("v1:"),
                    "legacy row should have been encrypted at startup; got: " + stored);
            assertEquals("plain-secret", crypto.decryptIfNeeded(stored));
        } finally {
            db.shutdown();
        }
    }

    @Test
    void startupFailsWhenPlaintextRowsExistButKeyMissing() {
        var db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(db);
            jdbc.update("""
                    insert into shipping_tracking_notification_account
                        (email, smtp_password, enabled, created_at, updated_at)
                    values ('legacy@example.com', 'plain-secret', true,
                            current_timestamp, current_timestamp)
                    """);

            ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService("", jdbc);
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    crypto::verifyKeyAvailableIfEncryptedRowsExist);
            assertTrue(error.getMessage().contains("plaintext"),
                    "error should mention plaintext rows; got: " + error.getMessage());
        } finally {
            db.shutdown();
        }
    }
}
