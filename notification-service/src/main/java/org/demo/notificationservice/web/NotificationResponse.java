package org.demo.notificationservice.web;

import org.demo.notificationservice.notification.Notification;
import org.demo.notificationservice.notification.NotificationChannel;
import org.demo.notificationservice.notification.NotificationEventType;
import org.demo.notificationservice.notification.NotificationStatus;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        NotificationEventType eventType,
        Long sourceId,
        String recipient,
        NotificationChannel channel,
        String subject,
        String message,
        NotificationStatus status,
        String providerReference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getEventType(),
                notification.getSourceId(),
                notification.getRecipient(),
                notification.getChannel(),
                notification.getSubject(),
                notification.getMessage(),
                notification.getStatus(),
                notification.getProviderReference(),
                notification.getFailureReason(),
                notification.getCreatedAt(),
                notification.getUpdatedAt(),
                notification.getSentAt()
        );
    }
}
