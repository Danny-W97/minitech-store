package org.demo.returnsservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record ReturnEventDataExportPage(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                        Long returnId, List<Event> events, boolean hasMore, String nextCursor,
                                        List<ReturnDataExportPage.Omission> omissions) {
    @Schema(name="ReturnExportEvent")
    public record Event(Long eventId, String action, String fromStatus, String toStatus, Instant createdAt) {}
}
