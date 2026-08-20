package org.demo.notificationservice.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "notification.delivery.provider",
        havingValue = "external-api"
)
public class ExternalApiNotificationDeliveryService implements NotificationDeliveryService {

    @Override
    public NotificationDeliveryResult deliver(Notification notification, boolean simulateFailure) {
        return NotificationDeliveryResult.failed(
                "External notification API is planned for a later production stage"
        );
    }
}
