package org.demo.notificationservice.notification;

public interface NotificationDeliveryService {

    NotificationDeliveryResult deliver(Notification notification, boolean simulateFailure);
}
