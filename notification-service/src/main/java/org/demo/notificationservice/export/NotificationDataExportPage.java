package org.demo.notificationservice.export;

import java.time.Instant;
import java.util.List;

public record NotificationDataExportPage(int schemaVersion,String scope,boolean complete,Instant exportedAt,
        String authorizationMode,long sourceId,Long conversationId,List<Entry> items,boolean hasMore,String nextCursor,List<Omission> omissions) {
    @io.swagger.v3.oas.annotations.media.Schema(name="NotificationExportEntry")
    public record Entry(long notificationId,String eventType,long sourceId,String recipient,String channel,
            String subject,String message,String status,String contentDisposition,Instant contentCompactedAt,
            Instant createdAt,Instant updatedAt,Instant sentAt) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="NotificationExportOmission")
    public record Omission(String area,String reason) {}
}
