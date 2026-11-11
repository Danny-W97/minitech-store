package org.demo.inventoryservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record InventoryItemDataExportPage(int schemaVersion,String scope,boolean complete,Instant generatedAt,
        long orderId,List<Item> items,boolean hasMore,String nextCursor,List<InventoryDataExport.Omission> omissions) {
    @Schema(name="InventoryExportReservationItem")
    public record Item(long reservationItemId,long reservationId,long productId,int quantity) {}
}
