package org.demo.billingservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceItemDataExportPage(int schemaVersion,String scope,boolean complete,Instant generatedAt,
                                        Long orderId,List<Item> items,boolean hasMore,String nextCursor,List<InvoiceDataExport.Omission> omissions) {
    @Schema(name="InvoiceExportLine")
    public record Item(Long itemId,int positionNo,Long productId,String sku,String productName,int quantity,
                       BigDecimal unitPrice,BigDecimal totalPrice) {}
}
