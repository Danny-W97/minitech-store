package org.demo.notificationservice.notification;

public enum NotificationEventType {
    ORDER_CREATED,
    PAYMENT_PENDING,
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    ORDER_STATUS_CHANGED,
    CUSTOMER_REGISTERED,
    PASSWORD_RESET_REQUESTED,
    CHAT_MESSAGE_RECEIVED,
    CHAT_REPLY_SENT
}
