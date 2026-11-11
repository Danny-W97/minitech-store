package org.demo.inventoryservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record InventoryDataExport(int schemaVersion,String scope,boolean complete,Instant generatedAt,
        long orderId,Reservation reservation,List<Omission> omissions) {
    @Schema(name="InventoryExportReservation")
    public record Reservation(long reservationId,long orderId,String status,Instant expiresAt,Instant createdAt,Instant updatedAt) {}
    @Schema(name="InventoryExportOmission")
    public record Omission(String area,String reason) {}
}
