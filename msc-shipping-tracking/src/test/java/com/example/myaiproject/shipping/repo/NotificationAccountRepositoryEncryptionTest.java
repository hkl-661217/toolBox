package com.example.myaiproject.shipping.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.support.ShippingTrackingCryptoService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationAccountRepositoryEncryptionTest {

    @Autowired NotificationAccountRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ShippingTrackingCryptoService crypto;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from shipping_tracking_notification_account");
    }

    @Test
    void insertStoresCiphertextOnDiskButReturnsPlaintextToCaller() {
        NotificationAccount account = repository.insert(
                "encrypt@example.com",
                "qq-auth-secret-123",
                OffsetDateTime.now());

        assertEquals("qq-auth-secret-123", account.smtpPassword());

        String stored = jdbcTemplate.queryForObject(
                "select smtp_password from shipping_tracking_notification_account where id = ?",
                String.class,
                account.id());
        assertTrue(stored.startsWith("v1:"), "stored value must be encrypted; got: " + stored);
        assertNotEquals("qq-auth-secret-123", stored);
        assertEquals("qq-auth-secret-123", crypto.decryptIfNeeded(stored));
    }

    @Test
    void findEnabledDecryptsTransparently() {
        repository.insert("read@example.com", "secret-456", OffsetDateTime.now());

        NotificationAccount loaded = repository.findEnabled().get(0);
        assertEquals("secret-456", loaded.smtpPassword());
    }

    @Test
    void legacyPlaintextRowKeepsWorking() {
        jdbcTemplate.update("""
                insert into shipping_tracking_notification_account
                    (email, smtp_password, enabled, created_at, updated_at)
                values (?, ?, true, ?, ?)
                """,
                "legacy@example.com",
                "old-plain-code",
                OffsetDateTime.now(),
                OffsetDateTime.now());

        NotificationAccount loaded = repository.findAll().get(0);
        assertEquals("old-plain-code", loaded.smtpPassword());
    }
}
