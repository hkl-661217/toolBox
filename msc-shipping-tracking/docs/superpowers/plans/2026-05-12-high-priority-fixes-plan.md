# High-Priority Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the three top-priority fixes from spec `docs/superpowers/specs/2026-05-12-high-priority-fixes-design.md`: encrypt SMTP credentials with AES-GCM, retry failed emails on a 15-minute cron, and replace `batch-limit=5` with a "requery after N hours" scheduling rule. Also fix the SLF4J vs `System.err` regression in the scheduler (high-priority item #6).

**Architecture:** All changes are server-side (Spring Boot 3.3.5 + Java 21 + JdbcTemplate + H2 file mode). Three orthogonal feature slices share one schema migration (two new columns on `shipping_tracking_change_log`). No new third-party dependencies — AES-GCM comes from the JDK. Encryption key is read from Spring property `shipping.tracking.encryption.key` (backed by env `SHIPPING_TRACKING_ENCRYPTION_KEY`), so tests can supply it via `@SpringBootTest(properties = …)`. The frontend HTML is not touched.

**Tech Stack:** Spring Boot 3.3.5, Java 21, JdbcTemplate, H2 (file mode in prod, in-mem in test), JUnit 5, AssertJ-via-JUnit assertions, Mockito (already on classpath via spring-boot-starter-test), Maven.

**Test command:** `mvn -B -f msc-shipping-tracking/pom.xml test` from repo root.

---

## File Structure

**Existing files modified:**
- `src/main/resources/schema.sql` — two `ALTER TABLE` statements (retry_count, last_retry_at)
- `src/main/resources/application.properties` — remove `batch-limit`, add `min-requery-hours` + `retry-*` + `encryption.key`
- `src/test/resources/application-test.properties` — drop `batch-limit`, set `encryption.key` test value
- `src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingProperties.java` — replace `batchLimit` with `minRequeryHours`; add `retryCron`, `retryMaxAttempts`, `retryMaxAgeHours`, `encryptionKey`
- `src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingScheduler.java` — use new repo method; replace `System.err.printf` with SLF4J
- `src/main/java/com/example/myaiproject/shipping/repo/ShippingTrackingBindingRepository.java` — add `findBindingsDueForQuery`
- `src/main/java/com/example/myaiproject/shipping/repo/NotificationAccountRepository.java` — pipe insert through crypto + decrypt in mapper
- `src/main/java/com/example/myaiproject/shipping/repo/ShippingTrackingChangeLogRepository.java` — add `findPendingRetries`, `markEmailSent`, `bumpRetryCount`, `findById`
- `src/main/java/com/example/myaiproject/shipping/model/ShippingTrackingChangeLog.java` — add `retryCount` and `lastRetryAt` fields
- `src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingService.java` — no behaviour change; relies on new schema defaults

**New files:**
- `src/main/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoService.java` — AES-GCM encrypt / decrypt + startup self-check
- `src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJob.java` — `@Scheduled` retry runner
- `src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoServiceTest.java`
- `src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoStartupTest.java`
- `src/test/java/com/example/myaiproject/shipping/repo/NotificationAccountRepositoryEncryptionTest.java`
- `src/test/java/com/example/myaiproject/shipping/repo/ShippingTrackingChangeLogRepositoryTest.java`
- `src/test/java/com/example/myaiproject/shipping/repo/ShippingTrackingBindingRepositoryDueTest.java`
- `src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJobTest.java`
- `src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingSchedulerRequeryTest.java`

**Test key** (used in all `*-test.properties` & dynamic-properties test contexts): base64 of 32 zero bytes — `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=`. Predictable, doesn't change across runs, distinct from any real prod key.

---

## Task 1: Replace `System.err.printf` with SLF4J in Scheduler

This is independent and low-risk. Doing it first builds confidence the test harness is green before bigger changes land.

**Files:**
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingScheduler.java:34-38`

- [ ] **Step 1: Inspect current scheduler imports and class header**

Run: `sed -n '1,20p' msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingScheduler.java`
Expected: confirms no `Logger` import and no static logger field.

- [ ] **Step 2: Add Logger field and replace `System.err.printf`**

Edit `ShippingTrackingScheduler.java`:

After the imports (line 9), insert:
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Inside the class body, before the existing `private final ShippingTrackingBindingRepository bindingRepository;` (line 13), insert:
```java
private static final Logger log = LoggerFactory.getLogger(ShippingTrackingScheduler.class);
```

Replace the existing block at lines 33-38:
```java
} catch (Exception error) {
    System.err.printf(
            "Shipping tracking batch failed for binding %d: %s%n",
            binding.id(),
            error.getMessage());
}
```
with:
```java
} catch (Exception error) {
    log.warn("Shipping tracking batch failed for binding {}.", binding.id(), error);
}
```

- [ ] **Step 3: Compile and run the full suite to ensure no regression**

Run: `mvn -B -f msc-shipping-tracking/pom.xml test`
Expected: BUILD SUCCESS; existing 16 tests pass.

- [ ] **Step 4: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingScheduler.java
git commit -m "fix: route scheduler failure messages through SLF4J

System.err.printf bypassed the logback config so batch failures
never landed in logs/msc-shipping-tracking.log. Switch to log.warn
so failures show up alongside everything else.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Add `retry_count` and `last_retry_at` columns to `shipping_tracking_change_log`

Schema migration sits on its own commit so we can confirm `add column if not exists` runs cleanly on both fresh and existing H2 files before any code depends on it.

**Files:**
- Modify: `msc-shipping-tracking/src/main/resources/schema.sql` (append after line 91)
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/model/ShippingTrackingChangeLog.java`

- [ ] **Step 1: Append the ALTERs to schema.sql**

Open `msc-shipping-tracking/src/main/resources/schema.sql`. After line 91 (`comment on column shipping_tracking_change_log.created_at is '创建时间';`), insert:

```sql

alter table shipping_tracking_change_log add column if not exists retry_count int not null default 0;
alter table shipping_tracking_change_log add column if not exists last_retry_at timestamp with time zone;
comment on column shipping_tracking_change_log.retry_count is '邮件重发次数（首次失败计为 0，每次补偿 +1）';
comment on column shipping_tracking_change_log.last_retry_at is '最近一次重发尝试时间（含失败）';
```

- [ ] **Step 2: Extend the `ShippingTrackingChangeLog` record**

Replace the entire file `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/model/ShippingTrackingChangeLog.java` with:

```java
package com.example.myaiproject.shipping.model;

import java.time.OffsetDateTime;

public record ShippingTrackingChangeLog(
        Long id,
        Long bindingId,
        Long previousSnapshotId,
        Long currentSnapshotId,
        String changeType,
        String changeSummary,
        String beforeJson,
        String afterJson,
        boolean emailSent,
        OffsetDateTime emailSentTime,
        int retryCount,
        OffsetDateTime lastRetryAt,
        OffsetDateTime createdAt) {
}
```

- [ ] **Step 3: Run tests — they must still pass since no caller constructs the record yet**

Run: `mvn -B -f msc-shipping-tracking/pom.xml test`
Expected: BUILD SUCCESS. (If any test constructs `ShippingTrackingChangeLog` directly, the compiler will catch it; add the two new args as `0` and `null` at those call sites.)

- [ ] **Step 4: Commit**

```bash
git add msc-shipping-tracking/src/main/resources/schema.sql \
        msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/model/ShippingTrackingChangeLog.java
git commit -m "feat: add retry_count + last_retry_at to change_log schema

Prep for the email retry job. Columns default to 0 / null so the
existing change-log insert path keeps working unchanged.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: ShippingTrackingCryptoService — round-trip encrypt / decrypt (red → green)

Start with the simplest behaviour: a 32-byte AES key encrypts a plaintext and decrypts it back. Defer plaintext-fallback and startup-check to later tasks.

**Files:**
- Create: `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoServiceTest.java`
- Create: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoService.java`

- [ ] **Step 1: Write the failing round-trip test**

Create `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoServiceTest.java`:

```java
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
        // GCM uses a fresh IV per encrypt, so two encrypts of the same input must
        // produce two different stored values. Catches accidental IV reuse.
        ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService(TEST_KEY);

        String a = crypto.encrypt("same-secret");
        String b = crypto.encrypt("same-secret");

        assertNotEquals(a, b);
        assertEquals("same-secret", crypto.decryptIfNeeded(a));
        assertEquals("same-secret", crypto.decryptIfNeeded(b));
    }
}
```

- [ ] **Step 2: Run to verify it fails (compile error — class does not exist yet)**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingCryptoServiceTest test`
Expected: COMPILATION ERROR — `cannot find symbol: class ShippingTrackingCryptoService`.

- [ ] **Step 3: Write the minimal implementation**

Create `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoService.java`:

```java
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
            // Legacy plaintext row — keep it usable; a later write will re-encrypt.
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
```

- [ ] **Step 4: Run the tests to verify green**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingCryptoServiceTest test`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoService.java \
        msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoServiceTest.java
git commit -m "feat: add AES-GCM crypto service for SMTP credentials

Versioned 'v1:<iv>:<ct>' format. JDK-only — no new dependencies.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Plaintext fallback and malformed-ciphertext rejection

Two more behavior tests to lock down the legacy-plaintext and tamper-detection contracts.

**Files:**
- Modify: `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoServiceTest.java`

- [ ] **Step 1: Add the failing tests**

Append inside the test class (before the closing brace):

```java
    @Test
    void decryptIfNeededReturnsLegacyPlaintextAsIs() {
        ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService(TEST_KEY);

        // Row written before encryption was rolled out — no v1: prefix.
        assertEquals("old-plain-value", crypto.decryptIfNeeded("old-plain-value"));
    }

    @Test
    void decryptIfNeededRejectsTamperedCiphertext() {
        ShippingTrackingCryptoService crypto = new ShippingTrackingCryptoService(TEST_KEY);

        String stored = crypto.encrypt("real-secret");
        // Flip the last character of the base64 ciphertext to corrupt the GCM tag.
        char[] chars = stored.toCharArray();
        chars[chars.length - 1] = (chars[chars.length - 1] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> crypto.decryptIfNeeded(tampered));
        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().contains("decrypt"),
                "Error must mention decryption; actual: " + error.getMessage());
    }
```

- [ ] **Step 2: Run tests**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingCryptoServiceTest test`
Expected: All 4 tests pass — the existing implementation from Task 3 already handles both paths (the non-`v1:` short-circuit and the `GeneralSecurityException` wrap).

If `decryptIfNeededRejectsTamperedCiphertext` happens to pass by hitting the malformed-prefix branch instead of GCM, the assertion `error.getMessage().contains("decrypt")` would still pass (the message says "Failed to decrypt"). Both branches are acceptable failure paths for tampered input.

- [ ] **Step 3: Commit**

```bash
git add msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoServiceTest.java
git commit -m "test: cover plaintext fallback and tampered-ciphertext rejection

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: Wire CryptoService into Spring + startup self-check

Make the service a Spring bean reading from `shipping.tracking.encryption.key`. Add the "v1 rows exist but key missing → fail fast" check.

**Files:**
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingProperties.java`
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoService.java`
- Modify: `msc-shipping-tracking/src/main/resources/application.properties`
- Modify: `msc-shipping-tracking/src/test/resources/application-test.properties`
- Create: `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoStartupTest.java`

- [ ] **Step 1: Add the `encryptionKey` field to `ShippingTrackingProperties`**

Open `ShippingTrackingProperties.java`. After line 21 (`private long curlImpersonateTimeoutMs = 90_000L;`), add:

```java
    private String encryptionKey = "";
```

After the existing `getCurlImpersonateTimeoutMs()` / setter pair (around line 124), append:

```java
    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
```

- [ ] **Step 2: Add `application.properties` and `application-test.properties` entries**

In `src/main/resources/application.properties`, append at the bottom:

```properties

# Base64-encoded 32-byte AES key for SMTP credential encryption.
# Mandatory once any v1:-prefixed rows exist; startup will fail otherwise.
shipping.tracking.encryption.key=${SHIPPING_TRACKING_ENCRYPTION_KEY:}
```

In `src/test/resources/application-test.properties`, append:

```properties
shipping.tracking.encryption.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
```

- [ ] **Step 3: Make `ShippingTrackingCryptoService` a Spring bean with startup self-check**

Replace the top of `ShippingTrackingCryptoService.java` (the imports + class declaration + existing constructor) with the following, **keeping the existing `encrypt` and `decryptIfNeeded` method bodies intact**:

```java
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ShippingTrackingCryptoService {
    static final String VERSION_PREFIX = "v1:";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private final String rawKey;
    private final JdbcTemplate jdbcTemplate;
    private SecretKeySpec keySpec; // nullable until @PostConstruct decides we need it
    private final SecureRandom random = new SecureRandom();

    public ShippingTrackingCryptoService(ShippingTrackingProperties properties, JdbcTemplate jdbcTemplate) {
        this(properties.getEncryptionKey(), jdbcTemplate);
    }

    // Visible-for-testing — lets unit tests construct without a JdbcTemplate.
    ShippingTrackingCryptoService(String base64Key) {
        this(base64Key, null);
    }

    ShippingTrackingCryptoService(String base64Key, JdbcTemplate jdbcTemplate) {
        this.rawKey = base64Key == null ? "" : base64Key;
        this.jdbcTemplate = jdbcTemplate;
        if (!this.rawKey.isBlank()) {
            this.keySpec = buildKeySpec(this.rawKey);
        }
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
        // ... existing body unchanged ...
```

**Important:** keep the body of `encrypt(...)` from "byte[] iv = new byte[IV_LENGTH_BYTES];" onward unchanged. Also keep the existing `decryptIfNeeded(...)` method intact, but **add this guard at its top**, after the `if (!stored.startsWith(VERSION_PREFIX)) return stored;` line:

```java
        if (keySpec == null) {
            throw new IllegalStateException(
                    "shipping.tracking.encryption.key is not set; cannot decrypt 'v1:' value.");
        }
```

(I.e. plaintext rows still pass through without a key; encrypted rows need a key.)

- [ ] **Step 4: Update the unit test to use the test-key constructor**

The existing `ShippingTrackingCryptoServiceTest` calls `new ShippingTrackingCryptoService(TEST_KEY)` which now hits the package-private `ShippingTrackingCryptoService(String)` constructor. Since this constructor delegates to `(base64Key, null)`, and `@PostConstruct` is not invoked outside Spring, the existing tests continue to work unchanged. **Verify by running them:**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingCryptoServiceTest test`
Expected: 4 tests pass.

- [ ] **Step 5: Write the startup-check failing test**

Create `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoStartupTest.java`:

```java
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
            // Stuff one encrypted-looking row in directly so the count query lights up.
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

            // Should NOT throw — fresh install with no encrypted rows yet.
            crypto.verifyKeyAvailableIfEncryptedRowsExist();
        } finally {
            db.shutdown();
        }
    }
}
```

- [ ] **Step 6: Run the new test**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingCryptoStartupTest test`
Expected: 2 tests pass.

- [ ] **Step 7: Run the entire suite — Spring boot context tests must still wire the bean cleanly**

Run: `mvn -B -f msc-shipping-tracking/pom.xml test`
Expected: BUILD SUCCESS. (Spring will autowire `ShippingTrackingCryptoService` via the new `(ShippingTrackingProperties, JdbcTemplate)` constructor. Test profile supplies the key.)

- [ ] **Step 8: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingProperties.java \
        msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoService.java \
        msc-shipping-tracking/src/main/resources/application.properties \
        msc-shipping-tracking/src/test/resources/application-test.properties \
        msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoStartupTest.java
git commit -m "feat: wire crypto service into Spring with startup self-check

Reads shipping.tracking.encryption.key (env SHIPPING_TRACKING_ENCRYPTION_KEY).
At startup, fails fast if any 'v1:'-prefixed rows exist but the key is unset,
preventing the service from sending ciphertext as a plaintext SMTP password.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Pipe `NotificationAccountRepository` through the crypto service

The repo encrypts on insert and decrypts in the row mapper. Service-layer code stays unchanged (still hands plaintext in, gets plaintext out).

**Files:**
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/repo/NotificationAccountRepository.java`
- Create: `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/NotificationAccountRepositoryEncryptionTest.java`

- [ ] **Step 1: Write the failing repo test**

Create `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/NotificationAccountRepositoryEncryptionTest.java`:

```java
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

        // What the caller sees: plaintext (so existing services keep working).
        assertEquals("qq-auth-secret-123", account.smtpPassword());

        // What is actually on disk: a v1: blob, not the plaintext.
        String stored = jdbcTemplate.queryForObject(
                "select smtp_password from shipping_tracking_notification_account where id = ?",
                String.class,
                account.id());
        assertTrue(stored.startsWith("v1:"), "stored value must be encrypted; got: " + stored);
        assertNotEquals("qq-auth-secret-123", stored);
        // Sanity: decrypts back to the original
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
        // Simulate a row that pre-dates encryption by inserting bare plaintext.
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=NotificationAccountRepositoryEncryptionTest test`
Expected: `insertStoresCiphertextOnDiskButReturnsPlaintextToCaller` FAILS — the stored value still equals the plaintext because the repo hasn't been wired yet.

- [ ] **Step 3: Wire the repo through the crypto service**

Replace `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/repo/NotificationAccountRepository.java` with:

```java
package com.example.myaiproject.shipping.repo;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.support.ShippingTrackingCryptoService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationAccountRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ShippingTrackingCryptoService crypto;

    public NotificationAccountRepository(JdbcTemplate jdbcTemplate, ShippingTrackingCryptoService crypto) {
        this.jdbcTemplate = jdbcTemplate;
        this.crypto = crypto;
    }

    public NotificationAccount insert(String email, String smtpPassword, OffsetDateTime now) {
        String encrypted = crypto.encrypt(smtpPassword);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into shipping_tracking_notification_account
                        (email, smtp_password, enabled, created_at, updated_at)
                    values (?, ?, true, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, email);
            ps.setString(2, encrypted);
            ps.setObject(3, now);
            ps.setObject(4, now);
            return ps;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public Optional<NotificationAccount> findById(long id) {
        List<NotificationAccount> rows = jdbcTemplate.query(
                "select * from shipping_tracking_notification_account where id = ?",
                mapper(),
                id);
        return rows.stream().findFirst();
    }

    public List<NotificationAccount> findAll() {
        return jdbcTemplate.query(
                "select * from shipping_tracking_notification_account order by id asc",
                mapper());
    }

    public List<NotificationAccount> findEnabled() {
        return jdbcTemplate.query(
                "select * from shipping_tracking_notification_account where enabled = true order by id asc",
                mapper());
    }

    public void delete(long id) {
        jdbcTemplate.update("delete from shipping_tracking_notification_account where id = ?", id);
    }

    public void setEnabled(long id, boolean enabled, OffsetDateTime now) {
        jdbcTemplate.update(
                "update shipping_tracking_notification_account set enabled = ?, updated_at = ? where id = ?",
                enabled,
                now,
                id);
    }

    private RowMapper<NotificationAccount> mapper() {
        return (rs, rowNum) -> new NotificationAccount(
                rs.getLong("id"),
                rs.getString("email"),
                crypto.decryptIfNeeded(rs.getString("smtp_password")),
                rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
```

- [ ] **Step 4: Run the encryption test + the full suite**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=NotificationAccountRepositoryEncryptionTest test`
Expected: 3 tests pass.

Run: `mvn -B -f msc-shipping-tracking/pom.xml test`
Expected: BUILD SUCCESS — `NotificationAccountToggleTest` still passes because it creates accounts via the service then reads `enabled` flag (it never inspects the raw password column).

- [ ] **Step 5: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/repo/NotificationAccountRepository.java \
        msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/NotificationAccountRepositoryEncryptionTest.java
git commit -m "feat: encrypt SMTP credentials at the repository boundary

Plaintext stays in-memory inside the service/controller layer.
On-disk values become AES-GCM ciphertext with a v1: prefix.
Legacy plaintext rows continue to load transparently.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: Add `findBindingsDueForQuery` and remove `batchLimit` plumbing

Item #3, half 1: a new repo method that picks bindings ordered by `last_query_time` and only those older than a threshold (or never queried).

**Files:**
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/repo/ShippingTrackingBindingRepository.java`
- Create: `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/ShippingTrackingBindingRepositoryDueTest.java`

- [ ] **Step 1: Write the failing repo test**

Create `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/ShippingTrackingBindingRepositoryDueTest.java`:

```java
package com.example.myaiproject.shipping.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ShippingTrackingBindingRepositoryDueTest {

    @Autowired ShippingTrackingBindingRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from shipping_tracking_change_log");
        jdbcTemplate.update("delete from shipping_tracking_snapshot");
        jdbcTemplate.update("delete from shipping_tracking_binding");
    }

    @Test
    void neverQueriedBindingComesFirst() {
        OffsetDateTime now = OffsetDateTime.now();
        ShippingTrackingBinding queriedRecently =
                repository.insert("ORDER-A", "BOOKING-A", now.minusDays(1));
        repository.updateAfterQuery(queriedRecently.id(), "SUCCESS", null, null, null, now, now);

        ShippingTrackingBinding neverQueried =
                repository.insert("ORDER-B", "BOOKING-B", now.minusHours(1));

        List<ShippingTrackingBinding> due =
                repository.findBindingsDueForQuery(now.minusHours(20));

        assertEquals(1, due.size(), "only the never-queried binding should be due");
        assertEquals(neverQueried.id(), due.get(0).id());
    }

    @Test
    void bindingOlderThanThresholdIsDue() {
        OffsetDateTime now = OffsetDateTime.now();
        ShippingTrackingBinding stale = repository.insert("ORDER-S", "BOOKING-S", now);
        repository.updateAfterQuery(stale.id(), "SUCCESS", null, null, null,
                now.minusHours(25), now.minusHours(25));

        List<ShippingTrackingBinding> due =
                repository.findBindingsDueForQuery(now.minusHours(20));

        assertEquals(1, due.size());
        assertEquals(stale.id(), due.get(0).id());
    }

    @Test
    void resultsOrderedByLastQueryTimeAscNullsFirst() {
        OffsetDateTime now = OffsetDateTime.now();
        ShippingTrackingBinding oldest = repository.insert("ORDER-1", "BOOKING-1", now);
        repository.updateAfterQuery(oldest.id(), "SUCCESS", null, null, null,
                now.minusHours(48), now.minusHours(48));

        ShippingTrackingBinding middle = repository.insert("ORDER-2", "BOOKING-2", now);
        repository.updateAfterQuery(middle.id(), "SUCCESS", null, null, null,
                now.minusHours(30), now.minusHours(30));

        ShippingTrackingBinding fresh = repository.insert("ORDER-3", "BOOKING-3", now);
        // never queried — last_query_time is null

        List<ShippingTrackingBinding> due =
                repository.findBindingsDueForQuery(now.minusHours(20));

        assertEquals(3, due.size());
        assertEquals(fresh.id(), due.get(0).id(), "null last_query_time should come first");
        assertEquals(oldest.id(), due.get(1).id(), "older queried binding next");
        assertEquals(middle.id(), due.get(2).id(), "more-recent queried binding last");
    }

    @Test
    void disabledBindingIsNeverDue() {
        OffsetDateTime now = OffsetDateTime.now();
        ShippingTrackingBinding disabled = repository.insert("ORDER-X", "BOOKING-X", now);
        repository.disable(disabled.id(), now);

        List<ShippingTrackingBinding> due =
                repository.findBindingsDueForQuery(now.minusHours(20));

        assertTrue(due.isEmpty(), "disabled bindings must not be picked up");
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingBindingRepositoryDueTest test`
Expected: COMPILATION ERROR — `cannot find symbol: method findBindingsDueForQuery`.

- [ ] **Step 3: Add the new repo method**

In `ShippingTrackingBindingRepository.java`, after the existing `findEnabled(int limit)` method (line 74), add:

```java
    public List<ShippingTrackingBinding> findBindingsDueForQuery(OffsetDateTime threshold) {
        return jdbcTemplate.query("""
                select * from shipping_tracking_binding
                where enabled = true
                  and (last_query_time is null or last_query_time < ?)
                order by last_query_time asc nulls first
                """,
                mapper(),
                threshold);
    }
```

- [ ] **Step 4: Run the new test**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingBindingRepositoryDueTest test`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/repo/ShippingTrackingBindingRepository.java \
        msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/ShippingTrackingBindingRepositoryDueTest.java
git commit -m "feat: add findBindingsDueForQuery for rotation-based scheduling

Replaces the batch-limit cap with a time-threshold + asc-nulls-first
ordering, so all enabled bindings rotate through over time.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Switch the scheduler to `findBindingsDueForQuery`; drop `batchLimit` plumbing

Item #3, half 2: the scheduler now picks "due" bindings, sized by hours-since-last-query rather than a fixed count.

**Files:**
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingProperties.java`
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingScheduler.java`
- Modify: `msc-shipping-tracking/src/main/resources/application.properties`
- Modify: `msc-shipping-tracking/src/test/resources/application-test.properties`
- Create: `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingSchedulerRequeryTest.java`

- [ ] **Step 1: Write the failing scheduler test**

Create `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingSchedulerRequeryTest.java`:

```java
package com.example.myaiproject.shipping.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.repo.ShippingTrackingBindingRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ShippingTrackingSchedulerRequeryTest {

    @Test
    void picksBindingsDueForQueryAndUsesConfiguredHoursThreshold() {
        ShippingTrackingBindingRepository repo = Mockito.mock(ShippingTrackingBindingRepository.class);
        ShippingTrackingService service = Mockito.mock(ShippingTrackingService.class);
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setMinRequeryHours(20);
        properties.setDelayMinSeconds(0);
        properties.setDelayMaxSeconds(0);

        ShippingTrackingBinding a = sampleBinding(1L);
        ShippingTrackingBinding b = sampleBinding(2L);
        when(repo.findBindingsDueForQuery(any())).thenReturn(List.of(a, b));

        ShippingTrackingScheduler scheduler =
                new ShippingTrackingScheduler(repo, service, properties);
        scheduler.runDailyBatch();

        ArgumentCaptor<OffsetDateTime> threshold = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).findBindingsDueForQuery(threshold.capture());

        OffsetDateTime now = OffsetDateTime.now();
        long minutesBetween = java.time.Duration.between(threshold.getValue(), now).toMinutes();
        // ~20 hours ago, allow generous slack so the test isn't time-flaky
        org.junit.jupiter.api.Assertions.assertTrue(
                minutesBetween >= 60 * 19 && minutesBetween <= 60 * 21,
                "threshold should be roughly 20h before now; was " + minutesBetween + " min");

        verify(service, times(1)).syncBindingForBatch(a);
        verify(service, times(1)).syncBindingForBatch(b);
    }

    @Test
    void emptyResultMakesNoServiceCalls() {
        ShippingTrackingBindingRepository repo = Mockito.mock(ShippingTrackingBindingRepository.class);
        ShippingTrackingService service = Mockito.mock(ShippingTrackingService.class);
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setMinRequeryHours(20);

        when(repo.findBindingsDueForQuery(any())).thenReturn(List.of());

        ShippingTrackingScheduler scheduler =
                new ShippingTrackingScheduler(repo, service, properties);
        scheduler.runDailyBatch();

        verify(service, never()).syncBindingForBatch(any());
    }

    private static ShippingTrackingBinding sampleBinding(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ShippingTrackingBinding(
                id, "ORDER-" + id, "BOOKING-" + id, "MSC", true,
                null, null, null, null, null, now, now);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingSchedulerRequeryTest test`
Expected: COMPILATION ERROR — `setMinRequeryHours` and `findBindingsDueForQuery` arg type don't yet flow through scheduler.

- [ ] **Step 3: Replace `batchLimit` with `minRequeryHours` in `ShippingTrackingProperties`**

In `ShippingTrackingProperties.java`, replace the line:

```java
    private int batchLimit = 5;
```

with:

```java
    private int minRequeryHours = 20;
```

Replace the existing `getBatchLimit()` / `setBatchLimit(...)` pair (lines 39-45) with:

```java
    public int getMinRequeryHours() {
        return minRequeryHours;
    }

    public void setMinRequeryHours(int minRequeryHours) {
        this.minRequeryHours = minRequeryHours;
    }
```

- [ ] **Step 4: Rewrite `ShippingTrackingScheduler.runDailyBatch`**

Replace the contents of `ShippingTrackingScheduler.java`'s `runDailyBatch()` body (lines 26-43) with:

```java
    @Scheduled(cron = "${shipping.tracking.cron:0 0 9 * * *}")
    public void runDailyBatch() {
        OffsetDateTime threshold = OffsetDateTime.now()
                .minusHours(properties.getMinRequeryHours());
        List<ShippingTrackingBinding> bindings =
                bindingRepository.findBindingsDueForQuery(threshold);
        for (int i = 0; i < bindings.size(); i++) {
            ShippingTrackingBinding binding = bindings.get(i);
            try {
                trackingService.syncBindingForBatch(binding);
            } catch (Exception error) {
                log.warn("Shipping tracking batch failed for binding {}.", binding.id(), error);
            }
            if (i < bindings.size() - 1) {
                sleepBetweenBindings();
            }
        }
    }
```

Add `import java.time.OffsetDateTime;` near the other imports if not already there.

- [ ] **Step 5: Update `application.properties`**

In `src/main/resources/application.properties`:
- Remove the line `shipping.tracking.batch-limit=5`
- Add (in the same section):

```properties
shipping.tracking.min-requery-hours=20
```

In `src/test/resources/application-test.properties`:
- Remove the line `shipping.tracking.batch-limit=5`
- Add:

```properties
shipping.tracking.min-requery-hours=20
```

- [ ] **Step 6: Run the new test plus the full suite**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingSchedulerRequeryTest test`
Expected: 2 tests pass.

Run: `mvn -B -f msc-shipping-tracking/pom.xml test`
Expected: BUILD SUCCESS. (No remaining test reads `batchLimit`. If one does, the compile error will point right at it — replace its setter with `setMinRequeryHours`.)

- [ ] **Step 7: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingProperties.java \
        msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingScheduler.java \
        msc-shipping-tracking/src/main/resources/application.properties \
        msc-shipping-tracking/src/test/resources/application-test.properties \
        msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingSchedulerRequeryTest.java
git commit -m "feat: drop batch-limit, requery bindings older than N hours

All enabled bindings now rotate through scheduling, ordered by
last_query_time asc nulls first. The min-requery-hours property
prevents the same binding from being re-queried twice in a day.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: Extend `ShippingTrackingChangeLogRepository` for retry queries + updates

Add the read/update methods the retry job needs. Service-layer insert path keeps working because `retry_count` defaults to `0` at the DB level.

**Files:**
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/repo/ShippingTrackingChangeLogRepository.java`
- Create: `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/ShippingTrackingChangeLogRepositoryTest.java`

- [ ] **Step 1: Write the failing repo test**

Create `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/ShippingTrackingChangeLogRepositoryTest.java`:

```java
package com.example.myaiproject.shipping.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingChangeLog;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ShippingTrackingChangeLogRepositoryTest {

    @Autowired ShippingTrackingChangeLogRepository changeLogRepository;
    @Autowired ShippingTrackingBindingRepository bindingRepository;
    @Autowired ShippingTrackingSnapshotRepository snapshotRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from shipping_tracking_change_log");
        jdbcTemplate.update("delete from shipping_tracking_snapshot");
        jdbcTemplate.update("delete from shipping_tracking_notification_account");
        jdbcTemplate.update("delete from shipping_tracking_binding");
    }

    @Test
    void findPendingRetriesReturnsOnlyFailedRecentRowsBelowAttemptCap() {
        long failedId = seedChangeLog(false, 0, OffsetDateTime.now().minusHours(2));
        long sentId   = seedChangeLog(true,  0, OffsetDateTime.now().minusHours(2));
        long exhaustedId = seedChangeLog(false, 6, OffsetDateTime.now().minusHours(2));
        long staleId  = seedChangeLog(false, 0, OffsetDateTime.now().minusHours(30));

        List<ShippingTrackingChangeLog> pending = changeLogRepository.findPendingRetries(
                6,
                OffsetDateTime.now().minusHours(24));

        List<Long> ids = pending.stream().map(ShippingTrackingChangeLog::id).toList();
        assertEquals(List.of(failedId), ids, "only the unfinished, fresh, below-cap row qualifies");

        // Untouched IDs are silently ignored
        assertTrue(!ids.contains(sentId));
        assertTrue(!ids.contains(exhaustedId));
        assertTrue(!ids.contains(staleId));
    }

    @Test
    void markEmailSentSetsSentAndIncrementsRetryCount() {
        long id = seedChangeLog(false, 2, OffsetDateTime.now().minusHours(1));

        OffsetDateTime sentAt = OffsetDateTime.now();
        changeLogRepository.markEmailSent(id, sentAt);

        ShippingTrackingChangeLog after = changeLogRepository.findById(id).orElseThrow();
        assertTrue(after.emailSent());
        assertNotNull(after.emailSentTime());
        assertEquals(3, after.retryCount(), "retry count must bump even on success");
        assertNotNull(after.lastRetryAt());
    }

    @Test
    void bumpRetryCountIncrementsWithoutFlippingSent() {
        long id = seedChangeLog(false, 1, OffsetDateTime.now().minusHours(1));

        changeLogRepository.bumpRetryCount(id, OffsetDateTime.now());

        ShippingTrackingChangeLog after = changeLogRepository.findById(id).orElseThrow();
        assertEquals(2, after.retryCount());
        assertNotNull(after.lastRetryAt());
        assertEquals(false, after.emailSent());
    }

    private long seedChangeLog(boolean emailSent, int retryCount, OffsetDateTime createdAt) {
        OffsetDateTime now = OffsetDateTime.now();
        ShippingTrackingBinding binding = bindingRepository.insert(
                "ORDER-" + System.nanoTime(),
                "BOOKING-" + System.nanoTime(),
                now);
        var snapshot = snapshotRepository.insert(
                binding.id(), now, "SUCCESS", List.of(), "", null, null, null, null, false, now);

        return jdbcTemplate.queryForObject("""
                insert into shipping_tracking_change_log
                    (binding_id, previous_snapshot_id, current_snapshot_id,
                     change_type, change_summary, before_json, after_json,
                     email_sent, email_sent_time, retry_count, last_retry_at, created_at)
                values (?, ?, ?, 'EVENTS_CHANGED', 'seed', '[]', '[]',
                        ?, null, ?, null, ?)
                returning id
                """,
                Long.class,
                binding.id(),
                snapshot.id(),
                snapshot.id(),
                emailSent,
                retryCount,
                createdAt);
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingChangeLogRepositoryTest test`
Expected: COMPILATION ERROR — `findPendingRetries`, `markEmailSent`, `bumpRetryCount`, `findById` not defined.

- [ ] **Step 3: Add the new methods**

Replace `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/repo/ShippingTrackingChangeLogRepository.java` with:

```java
package com.example.myaiproject.shipping.repo;

import com.example.myaiproject.shipping.model.ShippingTrackingChangeLog;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ShippingTrackingChangeLogRepository {
    private final JdbcTemplate jdbcTemplate;

    public ShippingTrackingChangeLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(
            long bindingId,
            long previousSnapshotId,
            long currentSnapshotId,
            String changeType,
            String changeSummary,
            String beforeJson,
            String afterJson,
            boolean emailSent,
            OffsetDateTime emailSentTime,
            OffsetDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into shipping_tracking_change_log
                        (binding_id, previous_snapshot_id, current_snapshot_id, change_type,
                         change_summary, before_json, after_json, email_sent, email_sent_time, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, bindingId);
            statement.setLong(2, previousSnapshotId);
            statement.setLong(3, currentSnapshotId);
            statement.setString(4, changeType);
            statement.setString(5, changeSummary);
            statement.setString(6, beforeJson);
            statement.setString(7, afterJson);
            statement.setBoolean(8, emailSent);
            statement.setObject(9, emailSentTime);
            statement.setObject(10, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ShippingTrackingChangeLog> findById(long id) {
        List<ShippingTrackingChangeLog> rows = jdbcTemplate.query(
                "select * from shipping_tracking_change_log where id = ?",
                mapper(),
                id);
        return rows.stream().findFirst();
    }

    /**
     * Returns rows that still need an email sent, are within the retry window,
     * and have not yet hit the attempt cap. Ordered oldest-first so persistent
     * failures don't starve newer changes that might succeed.
     */
    public List<ShippingTrackingChangeLog> findPendingRetries(int maxAttempts, OffsetDateTime ageCutoff) {
        return jdbcTemplate.query("""
                select * from shipping_tracking_change_log
                where email_sent = false
                  and retry_count < ?
                  and created_at >= ?
                order by created_at asc
                """,
                mapper(),
                maxAttempts,
                ageCutoff);
    }

    public void markEmailSent(long id, OffsetDateTime sentAt) {
        jdbcTemplate.update("""
                update shipping_tracking_change_log
                set email_sent = true,
                    email_sent_time = ?,
                    retry_count = retry_count + 1,
                    last_retry_at = ?
                where id = ?
                """,
                sentAt,
                sentAt,
                id);
    }

    public void bumpRetryCount(long id, OffsetDateTime attemptAt) {
        jdbcTemplate.update("""
                update shipping_tracking_change_log
                set retry_count = retry_count + 1,
                    last_retry_at = ?
                where id = ?
                """,
                attemptAt,
                id);
    }

    private static RowMapper<ShippingTrackingChangeLog> mapper() {
        return (rs, rowNum) -> new ShippingTrackingChangeLog(
                rs.getLong("id"),
                rs.getLong("binding_id"),
                rs.getLong("previous_snapshot_id"),
                rs.getLong("current_snapshot_id"),
                rs.getString("change_type"),
                rs.getString("change_summary"),
                rs.getString("before_json"),
                rs.getString("after_json"),
                rs.getBoolean("email_sent"),
                rs.getObject("email_sent_time", OffsetDateTime.class),
                rs.getInt("retry_count"),
                rs.getObject("last_retry_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
```

- [ ] **Step 4: Run new test**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingChangeLogRepositoryTest test`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/repo/ShippingTrackingChangeLogRepository.java \
        msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/repo/ShippingTrackingChangeLogRepositoryTest.java
git commit -m "feat: change_log repo support for retry scanning and updates

findPendingRetries / markEmailSent / bumpRetryCount / findById back
the upcoming notification retry job.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: Add retry properties (`retry-cron`, `retry-max-attempts`, `retry-max-age-hours`)

Plumb the three new tunables through `ShippingTrackingProperties` and both `application*.properties` files. No behavior change yet — just config plumbing.

**Files:**
- Modify: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingProperties.java`
- Modify: `msc-shipping-tracking/src/main/resources/application.properties`
- Modify: `msc-shipping-tracking/src/test/resources/application-test.properties`

- [ ] **Step 1: Extend `ShippingTrackingProperties`**

In `ShippingTrackingProperties.java`, after the existing `private String encryptionKey = "";` line added in Task 5, add:

```java
    private String retryCron = "0 */15 * * * *";
    private int retryMaxAttempts = 6;
    private int retryMaxAgeHours = 24;
```

After the `getEncryptionKey/setEncryptionKey` pair, append:

```java
    public String getRetryCron() {
        return retryCron;
    }

    public void setRetryCron(String retryCron) {
        this.retryCron = retryCron;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public int getRetryMaxAgeHours() {
        return retryMaxAgeHours;
    }

    public void setRetryMaxAgeHours(int retryMaxAgeHours) {
        this.retryMaxAgeHours = retryMaxAgeHours;
    }
```

- [ ] **Step 2: Append to property files**

In `src/main/resources/application.properties`, add at the bottom:

```properties
shipping.tracking.retry-cron=0 */15 * * * *
shipping.tracking.retry-max-attempts=6
shipping.tracking.retry-max-age-hours=24
```

In `src/test/resources/application-test.properties`, override the cron so tests never accidentally fire it during a single `mvn test` run that takes >15 minutes:

```properties
# Never fire during tests — manual invocations cover behavior.
shipping.tracking.retry-cron=0 0 0 31 12 ?
shipping.tracking.retry-max-attempts=6
shipping.tracking.retry-max-age-hours=24
```

- [ ] **Step 3: Run full suite**

Run: `mvn -B -f msc-shipping-tracking/pom.xml test`
Expected: BUILD SUCCESS (no behavior change yet).

- [ ] **Step 4: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingProperties.java \
        msc-shipping-tracking/src/main/resources/application.properties \
        msc-shipping-tracking/src/test/resources/application-test.properties
git commit -m "feat: retry properties (cron / max-attempts / max-age-hours)

Defaults: every 15 min, 6 attempts, 24h ceiling. Test profile uses
a far-future cron to ensure tests trigger the job manually only.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: Build `ShippingTrackingNotificationRetryJob` (red → green)

The retry runner itself. We unit-test it with Mockito-style fakes so the test doesn't depend on QQ SMTP.

**Files:**
- Create: `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJob.java`
- Create: `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJobTest.java`

- [ ] **Step 1: Write the failing test**

Create `msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJobTest.java`:

```java
package com.example.myaiproject.shipping.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingChangeLog;
import com.example.myaiproject.shipping.notify.ShippingTrackingEmailTemplateBuilder;
import com.example.myaiproject.shipping.notify.TrackingNotificationSender;
import com.example.myaiproject.shipping.repo.ShippingTrackingBindingRepository;
import com.example.myaiproject.shipping.repo.ShippingTrackingChangeLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ShippingTrackingNotificationRetryJobTest {

    @Test
    void successfulSendMarksRowAndIncrementsRetryCount() {
        Fixtures fx = new Fixtures();
        ShippingTrackingChangeLog row = fx.changeLogRow(42L, /*retryCount=*/ 1);
        when(fx.changeLogRepo.findPendingRetries(eq(6), any())).thenReturn(List.of(row));
        when(fx.bindingRepo.findById(row.bindingId())).thenReturn(Optional.of(fx.binding));
        when(fx.accounts.listEnabled()).thenReturn(List.of());
        when(fx.sender.send(anyString(), anyString())).thenReturn(true);

        fx.job.runRetryCycle();

        verify(fx.sender, times(1)).send(anyString(), anyString());
        verify(fx.changeLogRepo, times(1)).markEmailSent(eq(42L), any());
        verify(fx.changeLogRepo, never()).bumpRetryCount(eq(42L), any());
    }

    @Test
    void failedSendOnlyBumpsRetryCount() {
        Fixtures fx = new Fixtures();
        ShippingTrackingChangeLog row = fx.changeLogRow(99L, /*retryCount=*/ 0);
        when(fx.changeLogRepo.findPendingRetries(eq(6), any())).thenReturn(List.of(row));
        when(fx.bindingRepo.findById(row.bindingId())).thenReturn(Optional.of(fx.binding));
        when(fx.accounts.listEnabled()).thenReturn(List.of());
        when(fx.sender.send(anyString(), anyString())).thenReturn(false);

        fx.job.runRetryCycle();

        verify(fx.changeLogRepo, never()).markEmailSent(eq(99L), any());
        verify(fx.changeLogRepo, times(1)).bumpRetryCount(eq(99L), any());
    }

    @Test
    void perAccountSendUsesSendAsAndAnySuccessMarksSent() {
        Fixtures fx = new Fixtures();
        ShippingTrackingChangeLog row = fx.changeLogRow(7L, 0);
        when(fx.changeLogRepo.findPendingRetries(eq(6), any())).thenReturn(List.of(row));
        when(fx.bindingRepo.findById(row.bindingId())).thenReturn(Optional.of(fx.binding));

        NotificationAccount a = new NotificationAccount(
                1L, "a@example.com", "pwa", true,
                OffsetDateTime.now(), OffsetDateTime.now());
        NotificationAccount b = new NotificationAccount(
                2L, "b@example.com", "pwb", true,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(fx.accounts.listEnabled()).thenReturn(List.of(a, b));
        when(fx.sender.sendAs(anyString(), anyString(), eq("a@example.com"), anyString()))
                .thenReturn(false);
        when(fx.sender.sendAs(anyString(), anyString(), eq("b@example.com"), anyString()))
                .thenReturn(true);

        fx.job.runRetryCycle();

        verify(fx.sender, times(2)).sendAs(anyString(), anyString(), anyString(), anyString());
        verify(fx.sender, never()).send(anyString(), anyString());
        verify(fx.changeLogRepo, times(1)).markEmailSent(eq(7L), any());
    }

    @Test
    void rowWithoutKnownBindingIsSkippedSafely() {
        Fixtures fx = new Fixtures();
        ShippingTrackingChangeLog row = fx.changeLogRow(11L, 1);
        when(fx.changeLogRepo.findPendingRetries(eq(6), any())).thenReturn(List.of(row));
        when(fx.bindingRepo.findById(row.bindingId())).thenReturn(Optional.empty());

        fx.job.runRetryCycle();  // must not throw

        verify(fx.sender, never()).send(anyString(), anyString());
        verify(fx.sender, never()).sendAs(anyString(), anyString(), anyString(), anyString());
        verify(fx.changeLogRepo, never()).markEmailSent(eq(11L), any());
        verify(fx.changeLogRepo, never()).bumpRetryCount(eq(11L), any());
    }

    /** Small helper bag of mocks + a job wired up. */
    private static class Fixtures {
        final ShippingTrackingChangeLogRepository changeLogRepo =
                Mockito.mock(ShippingTrackingChangeLogRepository.class);
        final ShippingTrackingBindingRepository bindingRepo =
                Mockito.mock(ShippingTrackingBindingRepository.class);
        final TrackingNotificationSender sender =
                Mockito.mock(TrackingNotificationSender.class);
        final NotificationAccountService accounts =
                Mockito.mock(NotificationAccountService.class);
        final ShippingTrackingEmailTemplateBuilder templateBuilder =
                new ShippingTrackingEmailTemplateBuilder();
        final ObjectMapper objectMapper = new ObjectMapper();
        final ShippingTrackingProperties properties = new ShippingTrackingProperties();
        final ShippingTrackingBinding binding;
        final ShippingTrackingNotificationRetryJob job;

        Fixtures() {
            properties.setRetryMaxAttempts(6);
            properties.setRetryMaxAgeHours(24);
            OffsetDateTime now = OffsetDateTime.now();
            binding = new ShippingTrackingBinding(
                    500L, "ORDER-500", "BOOKING-500", "MSC", true,
                    "SUCCESS", "2026-06-01", "Loaded at port", null, null, now, now);
            job = new ShippingTrackingNotificationRetryJob(
                    changeLogRepo, bindingRepo, sender, accounts, templateBuilder,
                    objectMapper, properties);
        }

        ShippingTrackingChangeLog changeLogRow(long id, int retryCount) {
            return new ShippingTrackingChangeLog(
                    id, binding.id(), 1L, 2L,
                    "EVENTS_CHANGED",
                    "1 条变化",
                    "[]",
                    "[]",
                    false,
                    null,
                    retryCount,
                    null,
                    OffsetDateTime.now().minusHours(1));
        }
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingNotificationRetryJobTest test`
Expected: COMPILATION ERROR — `ShippingTrackingNotificationRetryJob` and its constructor do not exist.

- [ ] **Step 3: Implement the retry job**

Create `msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJob.java`:

```java
package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingChangeLog;
import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.model.ShippingTrackingEventChange;
import com.example.myaiproject.shipping.notify.ShippingTrackingEmailTemplateBuilder;
import com.example.myaiproject.shipping.notify.TrackingNotificationSender;
import com.example.myaiproject.shipping.repo.ShippingTrackingBindingRepository;
import com.example.myaiproject.shipping.repo.ShippingTrackingChangeLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ShippingTrackingNotificationRetryJob {
    private static final Logger log = LoggerFactory.getLogger(ShippingTrackingNotificationRetryJob.class);

    private final ShippingTrackingChangeLogRepository changeLogRepository;
    private final ShippingTrackingBindingRepository bindingRepository;
    private final TrackingNotificationSender sender;
    private final NotificationAccountService accountService;
    private final ShippingTrackingEmailTemplateBuilder templateBuilder;
    private final ObjectMapper objectMapper;
    private final ShippingTrackingProperties properties;

    public ShippingTrackingNotificationRetryJob(
            ShippingTrackingChangeLogRepository changeLogRepository,
            ShippingTrackingBindingRepository bindingRepository,
            TrackingNotificationSender sender,
            NotificationAccountService accountService,
            ShippingTrackingEmailTemplateBuilder templateBuilder,
            ObjectMapper objectMapper,
            ShippingTrackingProperties properties) {
        this.changeLogRepository = changeLogRepository;
        this.bindingRepository = bindingRepository;
        this.sender = sender;
        this.accountService = accountService;
        this.templateBuilder = templateBuilder;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(cron = "${shipping.tracking.retry-cron:0 */15 * * * *}")
    public void runScheduledRetry() {
        runRetryCycle();
    }

    /** Visible for tests — same logic, no @Scheduled wrapper. */
    void runRetryCycle() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime ageCutoff = now.minusHours(properties.getRetryMaxAgeHours());
        List<ShippingTrackingChangeLog> pending = changeLogRepository.findPendingRetries(
                properties.getRetryMaxAttempts(),
                ageCutoff);
        if (pending.isEmpty()) {
            return;
        }
        log.info("Email retry cycle scanning {} pending change_log row(s).", pending.size());

        for (ShippingTrackingChangeLog row : pending) {
            try {
                attemptOne(row);
            } catch (Exception error) {
                log.warn("Retry attempt threw for change_log id {}; bumping count.", row.id(), error);
                safeBumpRetry(row.id());
            }
        }
    }

    private void attemptOne(ShippingTrackingChangeLog row) {
        ShippingTrackingBinding binding = bindingRepository.findById(row.bindingId()).orElse(null);
        if (binding == null) {
            log.warn("Skipping retry for change_log {}: binding {} no longer exists.",
                    row.id(), row.bindingId());
            return;
        }

        List<ShippingTrackingEventChange> changes = rebuildChanges(row);
        ShippingTrackingEmailTemplateBuilder.EmailContent email =
                templateBuilder.buildChangeNotification(
                        binding,
                        binding.lastEta(),
                        binding.lastNode(),
                        changes,
                        OffsetDateTime.now());

        boolean delivered = dispatch(email);

        OffsetDateTime now = OffsetDateTime.now();
        if (delivered) {
            changeLogRepository.markEmailSent(row.id(), now);
        } else {
            changeLogRepository.bumpRetryCount(row.id(), now);
        }
    }

    private boolean dispatch(ShippingTrackingEmailTemplateBuilder.EmailContent email) {
        List<NotificationAccount> accounts = accountService.listEnabled();
        if (accounts.isEmpty()) {
            return sender.send(email.subject(), email.htmlBody());
        }
        boolean anySuccess = false;
        for (NotificationAccount account : accounts) {
            if (sender.sendAs(email.subject(), email.htmlBody(), account.email(), account.smtpPassword())) {
                anySuccess = true;
            }
        }
        return anySuccess;
    }

    private List<ShippingTrackingEventChange> rebuildChanges(ShippingTrackingChangeLog row) {
        try {
            List<ShippingTrackingEvent> before = readEvents(row.beforeJson());
            List<ShippingTrackingEvent> after = readEvents(row.afterJson());
            List<ShippingTrackingEventChange> out = new ArrayList<>();
            int size = Math.max(before.size(), after.size());
            for (int i = 0; i < size; i++) {
                ShippingTrackingEvent b = i < before.size() ? before.get(i) : null;
                ShippingTrackingEvent a = i < after.size() ? after.get(i) : null;
                out.add(new ShippingTrackingEventChange(i + 1, "RETRY", b, a));
            }
            return out;
        } catch (Exception error) {
            log.warn("Failed to rebuild events for change_log {}; sending an empty-changes email.",
                    row.id(), error);
            return List.of();
        }
    }

    private List<ShippingTrackingEvent> readEvents(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<List<ShippingTrackingEvent>>() {});
    }

    private void safeBumpRetry(long id) {
        try {
            changeLogRepository.bumpRetryCount(id, OffsetDateTime.now());
        } catch (Exception bumpError) {
            log.error("Failed to bump retry_count for change_log {}.", id, bumpError);
        }
    }
}
```

- [ ] **Step 4: Run the new test**

Run: `mvn -B -f msc-shipping-tracking/pom.xml -Dtest=ShippingTrackingNotificationRetryJobTest test`
Expected: 4 tests pass.

- [ ] **Step 5: Run the full suite**

Run: `mvn -B -f msc-shipping-tracking/pom.xml test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJob.java \
        msc-shipping-tracking/src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJobTest.java
git commit -m "feat: email retry job for failed change_log notifications

Every 15 min (cron), scans email_sent=false rows under the attempt
cap and within the 24h window. On success marks emailSent + bumps
retry_count; on failure only bumps retry_count.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 12: Final regression run + verification snapshot

End-to-end sanity check: full suite green, code review touchpoints.

**Files:**
- None (read-only verification)

- [ ] **Step 1: Run the entire test suite from a clean state**

Run:
```bash
mvn -B -f msc-shipping-tracking/pom.xml clean test
```
Expected: `BUILD SUCCESS` with all original tests + the new tests from this plan passing.

Recorded counts to verify (approximate — exact numbers depend on parameterized tests):

| Test class | New tests |
|---|---|
| `ShippingTrackingCryptoServiceTest` | +4 |
| `ShippingTrackingCryptoStartupTest` | +2 |
| `NotificationAccountRepositoryEncryptionTest` | +3 |
| `ShippingTrackingBindingRepositoryDueTest` | +4 |
| `ShippingTrackingSchedulerRequeryTest` | +2 |
| `ShippingTrackingChangeLogRepositoryTest` | +3 |
| `ShippingTrackingNotificationRetryJobTest` | +4 |

Total ≈ **22 new tests** on top of the existing suite.

- [ ] **Step 2: Confirm logs/ no longer receives `System.err`-style messages**

Run:
```bash
grep -n "System.err" msc-shipping-tracking/src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingScheduler.java || echo "ok - no System.err"
```
Expected: `ok - no System.err`.

- [ ] **Step 3: Confirm `batch-limit` is fully removed**

Run:
```bash
grep -rn "batch-limit\|batchLimit\|getBatchLimit" msc-shipping-tracking/src/main msc-shipping-tracking/src/test || echo "ok - batch-limit gone"
```
Expected: `ok - batch-limit gone`.

- [ ] **Step 4: Confirm `min-requery-hours` is set in both property files**

Run:
```bash
grep -n "min-requery-hours\|min-requery\|MinRequeryHours" msc-shipping-tracking/src/main/resources/application.properties msc-shipping-tracking/src/test/resources/application-test.properties
```
Expected: at least two hits (one per file), no spurious old `batch-limit`.

- [ ] **Step 5: Confirm `shipping_tracking_change_log` has the new columns at runtime**

Run:
```bash
mvn -B -f msc-shipping-tracking/pom.xml test -Dtest=ShippingTrackingChangeLogRepositoryTest 2>&1 | tail -5
```
Expected: tests pass — implies schema migration ran in H2 in-mem and the new columns exist.

- [ ] **Step 6: Final commit (only if any cleanup files emerged, e.g. unused imports)**

```bash
git status --short
```
If clean, skip this step. Otherwise:

```bash
git add -A
git commit -m "chore: post-implementation cleanup

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 7: Mention to user**

Tell the user: implementation complete, suite green, three high-priority items shipped. Surface follow-ups:
- **Production deployment requires setting `SHIPPING_TRACKING_ENCRYPTION_KEY`** in the systemd unit / `.env` / launch script. Without it the app starts only as long as no `v1:` rows exist; the first time someone creates a notification account, the encrypt call will throw and the request will fail.
- The two existing notification_account rows (if any) remain plaintext until next edit. Optional follow-up: an admin endpoint or a one-shot `update ... set smtp_password = ?` migration script. Out of scope for this plan.

---

## Risks & Notes (read before executing)

1. **`schema.sql` runs on every startup** (`spring.sql.init.mode=always`). All new statements use `add column if not exists` so re-running is safe. Tests run against an in-memory H2 each time, so the migration is exercised on every CI run.
2. **`@PostConstruct` on `ShippingTrackingCryptoService`** runs after `JdbcTemplate` is wired. It performs one `select count(*)` against `shipping_tracking_notification_account`. If the table does not exist yet (e.g., schema-init not finished), Spring's startup ordering ensures `spring.sql.init` runs first when datasource is initialized; for tests using `EmbeddedDatabaseBuilder` this is also guaranteed.
3. **Retry job and daily batch can overlap** since they're on different cron expressions. They operate on different rows (`change_log` vs `binding`), so no SQL deadlock risk on H2. If you later migrate to Postgres + multi-instance, revisit with `ShedLock`.
4. **The `RETRY` change-type label** is used by the retry job's `rebuildChanges` helper. The email template doesn't currently switch on change type for the human-readable column, so the body reads normally; if a future redesign of the email leans on `changeType` strings, audit this string at that time.
5. **Test-profile retry cron** `0 0 0 31 12 ?` is December 31 midnight, which won't fire during a typical CI run. We invoke `runRetryCycle()` directly from tests so the cron isn't exercised.

---

## Self-Review

- **Spec coverage:** All four spec sections (encryption / retry / scheduling / SLF4J cleanup) map to tasks. ✅
- **Placeholder scan:** No "TBD", "TODO", or "similar to Task N" without code. ✅
- **Type consistency:** `findBindingsDueForQuery(OffsetDateTime threshold)` referenced consistently in Tasks 7-8. `markEmailSent(long, OffsetDateTime)` / `bumpRetryCount(long, OffsetDateTime)` consistent across Tasks 9, 11. ✅
- **Test paths consistent:** All test files under `src/test/java/com/example/myaiproject/shipping/...` matching the production package. ✅
- **Schema column types match model:** `retry_count int not null default 0` → `int retryCount` in record. `last_retry_at timestamp with time zone` → `OffsetDateTime lastRetryAt`. ✅
