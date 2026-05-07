package com.example.myaiproject.shipping.model;

import java.time.OffsetDateTime;
import java.util.List;

public record ShippingTrackingSnapshot(
        Long id,
        Long bindingId,
        OffsetDateTime queryTime,
        String status,
        List<ShippingTrackingEvent> events,
        String rawText,
        String eta,
        String latestNode,
        String screenshotPath,
        String errorReason,
        boolean baseline,
        OffsetDateTime createdAt) {
}
