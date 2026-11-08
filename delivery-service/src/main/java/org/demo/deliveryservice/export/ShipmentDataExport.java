package org.demo.deliveryservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record ShipmentDataExport(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                 ShipmentData shipment, List<Omission> omissions) {
    @Schema(name="ShipmentExportData")
    public record ShipmentData(Long shipmentId, Long orderId, String trackingNumber, String carrier,
                               String methodCode, String status, Instant createdAt, Instant updatedAt) {}
    @Schema(name="ShipmentExportOmission")
    public record Omission(String scope, String status) {}
}
