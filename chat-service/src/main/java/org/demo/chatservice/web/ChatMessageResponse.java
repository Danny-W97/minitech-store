package org.demo.chatservice.web;

import org.demo.chatservice.chat.ChatMessage;
import org.demo.chatservice.chat.ChatMessageStatus;

import java.time.Instant;

public record ChatMessageResponse(
        Long id,
        String customerName,
        String customerEmail,
        String subject,
        String message,
        ChatMessageStatus status,
        String notificationStatus,
        String replyMessage,
        String replyNotificationStatus,
        Instant createdAt,
        Instant updatedAt,
        Instant repliedAt
) {
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(), message.getCustomerName(), message.getCustomerEmail(), message.getSubject(),
                message.getMessage(), message.getStatus(), message.getNotificationStatus(), message.getReplyMessage(),
                message.getReplyNotificationStatus(), message.getCreatedAt(), message.getUpdatedAt(), message.getRepliedAt());
    }
}
