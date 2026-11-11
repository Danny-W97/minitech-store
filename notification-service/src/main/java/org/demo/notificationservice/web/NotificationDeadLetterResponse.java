package org.demo.notificationservice.web;

import org.demo.notificationservice.notification.DeadLetterStatus;
import org.demo.notificationservice.notification.NotificationChannel;
import org.demo.notificationservice.notification.NotificationDeadLetterEntry;
import org.demo.notificationservice.notification.NotificationEventType;

import java.time.Instant;

public record NotificationDeadLetterResponse(
        Long id,
        Long notificationId,
        NotificationEventType eventType,
        Long sourceId,
        String recipient,
        NotificationChannel channel,
        String subject,
        String message,
        DeadLetterStatus status,
        String failureReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        boolean contentCompacted
) {

    public static NotificationDeadLetterResponse from(NotificationDeadLetterEntry entry) {
        return new NotificationDeadLetterResponse(
                entry.getId(),
                entry.getNotificationId(),
                entry.getEventType(),
                entry.getSourceId(),
                entry.getRecipient(),
                entry.getChannel(),
                entry.isContentCompacted() ? "Content removed" : entry.getSubject(),
                entry.isContentCompacted() ? "Content removed by retention policy." : entry.getMessage(),
                entry.getStatus(),
                entry.isContentCompacted() ? "Diagnostic removed by retention policy." : entry.getFailureReason(),
                entry.getAttemptCount(),
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getResolvedAt(),
                entry.isContentCompacted()
        );
    }
}
