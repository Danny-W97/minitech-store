package org.demo.notificationservice.notification;

public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(Long notificationId) {
        super("Notification %d was not found".formatted(notificationId));
    }
}
