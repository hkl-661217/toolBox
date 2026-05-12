package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import java.util.List;

public final class ShippingTrackingDepartureSelector {

    private ShippingTrackingDepartureSelector() {
    }

    public static Result select(List<ShippingTrackingEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        ShippingTrackingEvent actual = null;
        ShippingTrackingEvent estimated = null;
        for (ShippingTrackingEvent event : events) {
            if (event == null) {
                continue;
            }
            if (actual == null && ShippingTrackingChangeDetector.isCompletedDeparture(event)) {
                actual = event;
            } else if (estimated == null && ShippingTrackingChangeDetector.isEstimatedDeparture(event)) {
                estimated = event;
            }
        }
        if (actual != null) {
            return new Result("ACTUAL", format(actual));
        }
        if (estimated != null) {
            return new Result("ESTIMATED", format(estimated));
        }
        return null;
    }

    private static String format(ShippingTrackingEvent event) {
        return trim(event.date()) + "|" + trim(event.location()) + "|" + trim(event.description());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record Result(String kind, String formatted) {
    }
}
