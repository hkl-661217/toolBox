package com.example.myaiproject.shipping.client;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.tool.msc.MscTrackingStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record MscTrackingQueryResult(
        MscTrackingStatus status,
        String rawText,
        String currentStatus,
        String eta,
        String latestNode,
        List<ShippingTrackingEvent> events,
        String screenshotPath,
        String errorReason,
        OffsetDateTime queryTime) {
}
