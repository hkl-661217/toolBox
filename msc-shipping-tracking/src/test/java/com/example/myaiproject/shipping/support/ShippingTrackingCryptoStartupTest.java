package com.example.myaiproject.shipping.support;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ShippingTrackingCryptoStartupTest {

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
}
