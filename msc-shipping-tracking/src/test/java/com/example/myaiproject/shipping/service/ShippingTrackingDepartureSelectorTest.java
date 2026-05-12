package com.example.myaiproject.shipping.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShippingTrackingDepartureSelectorTest {

    @Test
    void returnsActualDepartureWhenPresent() {
        List<ShippingTrackingEvent> events = List.of(
                new ShippingTrackingEvent("12/05/2026", "Qingdao, CN", "Empty to Shipper", "EMPTY"),
                new ShippingTrackingEvent("18/05/2026", "Qingdao, CN", "Actual Time of Departure", "MSC DAISY KQ620A"),
                new ShippingTrackingEvent("16/06/2026", "Sydney, AU", "Estimated Time of Arrival", "MSC DAISY KQ620A"));

        ShippingTrackingDepartureSelector.Result result = ShippingTrackingDepartureSelector.select(events);

        assertEquals("ACTUAL", result.kind());
        assertEquals("18/05/2026|Qingdao, CN|Actual Time of Departure", result.formatted());
    }

    @Test
    void prefersActualOverEstimatedWhenBothPresent() {
        List<ShippingTrackingEvent> events = List.of(
                new ShippingTrackingEvent("18/05/2026", "Qingdao, CN", "Estimated Time of Departure", "MSC DAISY KQ620A"),
                new ShippingTrackingEvent("19/05/2026", "Qingdao, CN", "Actual Time of Departure", "MSC DAISY KQ620A"));

        ShippingTrackingDepartureSelector.Result result = ShippingTrackingDepartureSelector.select(events);

        assertEquals("ACTUAL", result.kind());
        assertEquals("19/05/2026|Qingdao, CN|Actual Time of Departure", result.formatted());
    }

    @Test
    void fallsBackToEstimatedWhenNoActual() {
        List<ShippingTrackingEvent> events = List.of(
                new ShippingTrackingEvent("12/05/2026", "Qingdao, CN", "Empty to Shipper", "EMPTY"),
                new ShippingTrackingEvent("18/05/2026", "Qingdao, CN", "Estimated Time of Departure", "MSC DAISY KQ620A"),
                new ShippingTrackingEvent("16/06/2026", "Sydney, AU", "Estimated Time of Arrival", "MSC DAISY KQ620A"));

        ShippingTrackingDepartureSelector.Result result = ShippingTrackingDepartureSelector.select(events);

        assertEquals("ESTIMATED", result.kind());
        assertEquals("18/05/2026|Qingdao, CN|Estimated Time of Departure", result.formatted());
    }

    @Test
    void returnsNullWhenNoDepartureEvent() {
        List<ShippingTrackingEvent> events = List.of(
                new ShippingTrackingEvent("16/06/2026", "Sydney, AU", "Estimated Time of Arrival", "MSC DAISY KQ620A"));

        ShippingTrackingDepartureSelector.Result result = ShippingTrackingDepartureSelector.select(events);

        assertNull(result);
    }

    @Test
    void emptyEventsReturnsNull() {
        assertNull(ShippingTrackingDepartureSelector.select(List.of()));
        assertNull(ShippingTrackingDepartureSelector.select(null));
    }
}
