package com.example.myaiproject.shipping.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingChangeLog;
import com.example.myaiproject.shipping.model.ShippingTrackingSnapshot;
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
        long failedId     = seedChangeLog(false, 0, OffsetDateTime.now().minusHours(2));
        long sentId       = seedChangeLog(true,  0, OffsetDateTime.now().minusHours(2));
        long exhaustedId  = seedChangeLog(false, 6, OffsetDateTime.now().minusHours(2));
        long staleId      = seedChangeLog(false, 0, OffsetDateTime.now().minusHours(30));

        List<ShippingTrackingChangeLog> pending = changeLogRepository.findPendingRetries(
                6,
                OffsetDateTime.now().minusHours(24));

        List<Long> ids = pending.stream().map(ShippingTrackingChangeLog::id).toList();
        assertEquals(List.of(failedId), ids, "only the unfinished, fresh, below-cap row qualifies");
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
    void findAgedOutReturnsOnlyUnsentRowsPastWindowAndNotYetGivenUp() {
        long agedId        = seedChangeLog(false, 0, OffsetDateTime.now().minusHours(30));
        long freshId       = seedChangeLog(false, 0, OffsetDateTime.now().minusHours(2));
        long agedButSentId = seedChangeLog(true,  0, OffsetDateTime.now().minusHours(30));

        OffsetDateTime ageCutoff = OffsetDateTime.now().minusHours(24);
        List<Long> ids = changeLogRepository.findAgedOut(ageCutoff).stream()
                .map(ShippingTrackingChangeLog::id).toList();

        assertEquals(List.of(agedId), ids);
        assertTrue(!ids.contains(freshId));
        assertTrue(!ids.contains(agedButSentId));

        changeLogRepository.markGivenUp(agedId, OffsetDateTime.now());
        assertEquals(0, changeLogRepository.findAgedOut(ageCutoff).size(),
                "already-marked rows should not be returned again");
    }

    @Test
    void givenUpRowsAreExcludedFromPendingRetries() {
        long id = seedChangeLog(false, 0, OffsetDateTime.now().minusHours(2));
        changeLogRepository.markGivenUp(id, OffsetDateTime.now());

        List<ShippingTrackingChangeLog> pending = changeLogRepository.findPendingRetries(
                6,
                OffsetDateTime.now().minusHours(24));
        assertEquals(0, pending.size(), "give_up_at IS NOT NULL must shield rows from retry");
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
        ShippingTrackingSnapshot snapshot = snapshotRepository.insert(
                binding.id(), now, "SUCCESS", List.of(), "", null, null, null, null, false, now);

        long id = changeLogRepository.insert(
                binding.id(),
                snapshot.id(),
                snapshot.id(),
                "EVENTS_CHANGED",
                "seed",
                "[]",
                "[]",
                emailSent,
                emailSent ? now : null,
                now);

        // Adjust the fields the public insert() doesn't take (created_at, retry_count).
        jdbcTemplate.update(
                "update shipping_tracking_change_log set created_at = ?, retry_count = ? where id = ?",
                createdAt,
                retryCount,
                id);
        return id;
    }
}
