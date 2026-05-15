package com.example.myaiproject.shipping.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.repo.ShippingTrackingBindingRepository;
import com.example.myaiproject.shipping.repo.ShippingTrackingChangeLogRepository;
import com.example.myaiproject.shipping.repo.ShippingTrackingSnapshotRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ShippingTrackingSchedulerRequeryTest {

    @Test
    void picksBindingsDueForQueryAndUsesConfiguredHoursThreshold() {
        OffsetDateTime now = OffsetDateTime.now();
        ShippingTrackingBinding a = sampleBinding(1L);
        ShippingTrackingBinding b = sampleBinding(2L);

        CapturingRepo repo = new CapturingRepo(List.of(a, b));
        RecordingService service = new RecordingService();
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setMinRequeryHours(20);
        properties.setDelayMinSeconds(0);
        properties.setDelayMaxSeconds(0);

        ShippingTrackingScheduler scheduler =
                new ShippingTrackingScheduler(repo, service, properties);
        scheduler.runDailyBatch();

        assertNotNull(repo.capturedThreshold, "scheduler must call findBindingsDueForQuery");
        long minutesBetween = java.time.Duration
                .between(repo.capturedThreshold, now)
                .toMinutes();
        assertTrue(minutesBetween >= 60 * 19 && minutesBetween <= 60 * 21,
                "threshold should be roughly 20h before now; was " + minutesBetween + " min");

        assertEquals(List.of(a.id(), b.id()),
                service.synced.stream().map(ShippingTrackingBinding::id).toList());
    }

    @Test
    void emptyResultMakesNoServiceCalls() {
        CapturingRepo repo = new CapturingRepo(List.of());
        RecordingService service = new RecordingService();
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setMinRequeryHours(20);

        ShippingTrackingScheduler scheduler =
                new ShippingTrackingScheduler(repo, service, properties);
        scheduler.runDailyBatch();

        assertEquals(List.of(), service.synced);
    }

    private static ShippingTrackingBinding sampleBinding(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ShippingTrackingBinding(
                id, "ORDER-" + id, "BOOKING-" + id, "MSC", true,
                null, null, null, null, null, now, now);
    }

    /** Hand-rolled test double — easier than fighting Mockito's inline-mock JVM agent. */
    private static class CapturingRepo extends ShippingTrackingBindingRepository {
        private final List<ShippingTrackingBinding> due;
        OffsetDateTime capturedThreshold;

        CapturingRepo(List<ShippingTrackingBinding> due) {
            super(new JdbcTemplate());
            this.due = due;
        }

        @Override
        public List<ShippingTrackingBinding> findBindingsDueForQuery(OffsetDateTime threshold) {
            this.capturedThreshold = threshold;
            return due;
        }
    }

    private static class RecordingService extends ShippingTrackingService {
        final List<ShippingTrackingBinding> synced = new ArrayList<>();

        RecordingService() {
            super(null, null, null, null, null, null, null, null, null,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(),
                    new ShippingTrackingMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        }

        @Override
        public void syncBindingForBatch(ShippingTrackingBinding binding) {
            synced.add(binding);
        }
    }
}
