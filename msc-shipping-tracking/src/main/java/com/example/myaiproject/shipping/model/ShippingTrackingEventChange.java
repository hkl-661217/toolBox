package com.example.myaiproject.shipping.model;

public record ShippingTrackingEventChange(
        int number,
        String changeType,
        ShippingTrackingEvent beforeEvent,
        ShippingTrackingEvent afterEvent) {
}
