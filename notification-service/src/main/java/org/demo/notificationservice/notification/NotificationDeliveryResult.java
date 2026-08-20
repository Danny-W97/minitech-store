package org.demo.notificationservice.notification;

public record NotificationDeliveryResult(
        boolean sent,
        String providerReference,
        String failureReason
) {

    public static NotificationDeliveryResult sent(String providerReference) {
        return new NotificationDeliveryResult(true, providerReference, null);
    }

    public static NotificationDeliveryResult failed(String failureReason) {
        return new NotificationDeliveryResult(false, null, failureReason);
    }
}
