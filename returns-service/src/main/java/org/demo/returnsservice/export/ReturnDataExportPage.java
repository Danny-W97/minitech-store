package org.demo.returnsservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import org.demo.returnsservice.returns.ReturnStatus;
import org.demo.returnsservice.returns.ReturnType;
import java.time.Instant;
import java.util.List;

public record ReturnDataExportPage(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                   List<ReturnData> returns, boolean hasMore, String nextCursor, List<Omission> omissions) {
    @Schema(name="ReturnExportData")
    public record ReturnData(Long returnId, Long orderId, String customerEmail, ReturnType requestType,
                             String reason, String description, ReturnStatus status, Instant createdAt, Instant updatedAt) {}
    @Schema(name="ReturnExportOmission")
    public record Omission(String scope, String status) {}
}
