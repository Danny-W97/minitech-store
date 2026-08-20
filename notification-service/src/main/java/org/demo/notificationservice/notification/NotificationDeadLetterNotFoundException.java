package org.demo.notificationservice.notification;

public class NotificationDeadLetterNotFoundException extends RuntimeException {

    public NotificationDeadLetterNotFoundException(Long deadLetterId) {
        super("Notification dead-letter entry %d was not found".formatted(deadLetterId));
    }
}
