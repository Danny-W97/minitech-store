package org.demo.chatservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record ChatEntryDataExportPage(int schemaVersion,String scope,boolean complete,Instant generatedAt,
                                      String authorizationMode,Long conversationId,List<Entry> entries,boolean hasMore,
                                      String nextCursor,List<ChatDataExport.Omission> omissions) {
    @Schema(name="ChatExportEntry")
    public record Entry(Long entryId,String author,boolean initialMessage,String message,Instant createdAt) {}
}
