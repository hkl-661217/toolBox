package com.example.myaiproject.shipping.model;

public record ShippingTrackingEvent(
        String date,
        String location,
        String description,
        String vesselVoyage) {
}
