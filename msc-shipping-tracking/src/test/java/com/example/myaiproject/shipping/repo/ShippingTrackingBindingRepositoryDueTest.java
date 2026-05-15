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
