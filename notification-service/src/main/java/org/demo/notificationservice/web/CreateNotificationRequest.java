package org.demo.notificationservice.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.demo.notificationservice.notification.NotificationChannel;
import org.demo.notificationservice.notification.NotificationEventType;

public record CreateNotificationRequest(
        @NotNull NotificationEventType eventType,
        @NotNull @Positive Long sourceId,
        @NotBlank @Email @Size(max = 255) String recipient,
        @NotNull NotificationChannel channel,
        @NotBlank @Size(max = 120) String subject,
        @NotBlank @Size(max = 6000) String message,
        boolean simulateFailure,
        Long invoiceId,
        @Positive Long customerId
) {
    public CreateNotificationRequest(NotificationEventType eventType,Long sourceId,String recipient,NotificationChannel channel,String subject,String message,boolean simulateFailure,Long invoiceId){this(eventType,sourceId,recipient,channel,subject,message,simulateFailure,invoiceId,null);}
    public CreateNotificationRequest(
            NotificationEventType eventType,
            Long sourceId,
            String recipient,
            NotificationChannel channel,
            String subject,
            String message,
            boolean simulateFailure
    ) {
        this(eventType, sourceId, recipient, channel, subject, message, simulateFailure, null,null);
    }
}
