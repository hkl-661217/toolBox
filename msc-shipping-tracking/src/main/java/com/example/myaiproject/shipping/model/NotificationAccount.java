package com.example.myaiproject.shipping.model;

import java.time.OffsetDateTime;

public record NotificationAccount(
        long id,
        String email,
        String smtpPassword,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
