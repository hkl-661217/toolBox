package com.example.myaiproject.shipping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.model.ShippingTrackingEventChange;
import com.example.myaiproject.shipping.service.ShippingTrackingChangeDetector;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShippingTrackingChangeDetectorTest {
    private final ShippingTrackingChangeDetector detector = new ShippingTrackingChangeDetector();

    @Test
    void estimatedDepartureDateChangeBeforeDepartureCreatesCustomerChange() {
        List<ShippingTrackingEventChange> changes = detector.detect(
                List.of(event("07/05/2026", "Qingdao, CN", "Estimated Time of Departure", "MSC MELANI III SE619A")),
                List.of(event("08/05/2026", "Qingdao, CN", "Estimated Time of Departure", "MSC MELANI III SE619A")));

        assertEquals(1, changes.size());
        assertEquals("ETD_CHANGED", changes.get(0).changeType());
    }

    @Test
    void estimatedDepartureBecomingActualDepartureDoesNotCreateCustomerChange() {
        List<ShippingTrackingEventChange> changes = detector.detect(
                List.of(event("07/05/2026", "Qingdao, CN", "Estimated Time of Departure", "MSC MELANI III SE619A")),
                List.of(event("08/05/2026", "Qingdao, CN", "Actual Time of Departure", "MSC MELANI III SE619A")));

        assertEquals(0, changes.size());
    }

    @Test
    void estimatedDepartureDisappearingWhenVesselDepartedDoesNotCreateCustomerChange() {
        List<ShippingTrackingEventChange> changes = detector.detect(
                List.of(event("07/05/2026", "Qingdao, CN", "Estimated Time of Departure", "MSC MELANI III SE619A")),
                List.of(event("08/05/2026", "Qingdao, CN", "Vessel Departed", "MSC MELANI III SE619A")));

        assertEquals(0, changes.size());
    }

    @Test
    void ordinaryInsertedEventStillCreatesCustomerChange() {
        ShippingTrackingEvent first = event("07/05/2026", "Qingdao, CN", "Loaded", "MSC MELANI III SE619A");
        ShippingTrackingEvent inserted = event("08/05/2026", "Busan, KR", "Transshipment", "MSC BUSAN 123A");

        List<ShippingTrackingEventChange> changes = detector.detect(
                List.of(first),
                List.of(first, inserted));

        assertEquals(1, changes.size());
        assertEquals("新增物流节点", changes.get(0).changeType());
    }

    private static ShippingTrackingEvent event(String date, String location, String description, String vesselVoyage) {
        return new ShippingTrackingEvent(date, location, description, vesselVoyage);
    }
}
