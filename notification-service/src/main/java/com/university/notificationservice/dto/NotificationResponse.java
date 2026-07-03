package com.university.notificationservice.dto;

import com.university.notificationservice.model.NotificationStatus;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        String recipient,
        String message,
        NotificationStatus status,
        Instant sentAt
) {
}
