package org.demo.chatservice.web;
import org.demo.chatservice.chat.*;
import java.time.Instant;
public record ChatEntryResponse(Long id,String author,boolean initialMessage,String message,String notificationStatus,Long notificationId,Instant createdAt) {
    public static ChatEntryResponse from(ChatEntry entry,ChatNotificationTask task) {
        return new ChatEntryResponse(entry.getId(),entry.getAuthor(),entry.isInitialMessage(),entry.getMessage(),task==null?"PENDING":task.publicStatus(),task==null?null:task.getNotificationId(),entry.getCreatedAt());
    }
}
