package org.demo.returnsservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record ReturnItemDataExportPage(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                       Long returnId, List<Item> items, boolean hasMore, String nextCursor,
                                       List<ReturnDataExportPage.Omission> omissions) {
    @Schema(name="ReturnExportLine")
    public record Item(Long lineId, Long orderItemId, Long productId, String productName, int orderedQuantity, int quantity) {}
}
