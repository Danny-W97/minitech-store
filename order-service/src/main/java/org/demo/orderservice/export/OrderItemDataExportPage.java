package org.demo.orderservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderItemDataExportPage(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                      Long orderId, List<Item> items, boolean hasMore, String nextCursor,
                                      List<OrderDataExportPage.Omission> omissions) {
    @Schema(name = "OrderExportLine")
    public record Item(Long itemId, Long productId, String sku, String productName, int quantity,
                       BigDecimal unitPrice, BigDecimal totalPrice) {}
}
