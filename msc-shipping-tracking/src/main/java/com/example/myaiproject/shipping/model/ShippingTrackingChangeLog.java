package com.example.myaiproject.shipping.model;

import java.time.OffsetDateTime;

public record ShippingTrackingChangeLog(
        Long id,
        Long bindingId,
        Long previousSnapshotId,
        Long currentSnapshotId,
        String changeType,
        String changeSummary,
        String beforeJson,
        String afterJson,
        boolean emailSent,
        OffsetDateTime emailSentTime,
        OffsetDateTime createdAt) {
}
