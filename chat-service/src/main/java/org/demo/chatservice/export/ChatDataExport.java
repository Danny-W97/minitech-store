package org.demo.chatservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record ChatDataExport(int schemaVersion,String scope,boolean complete,Instant generatedAt,
                              String authorizationMode,Conversation conversation,List<Omission> omissions) {
    @Schema(name="ChatExportConversation")
    public record Conversation(Long conversationId,String customerName,String customerEmail,String subject,String status,
                               String initialMessage,String latestReply,Instant createdAt,Instant updatedAt,Instant repliedAt) {}
    @Schema(name="ChatExportOmission")
    public record Omission(String scope,String status) {}
}
