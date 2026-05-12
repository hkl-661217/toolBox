package com.example.myaiproject.shipping.model;

import java.time.OffsetDateTime;

public record ShippingTrackingBinding(
        Long id,
        String orderNo,
        String bookingNo,
        String carrier,
        boolean enabled,
        String lastStatus,
        String lastEta,
        String lastNode,
        String lastDeparture,
        OffsetDateTime lastQueryTime,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
