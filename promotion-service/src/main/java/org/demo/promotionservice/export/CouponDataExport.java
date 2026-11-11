package org.demo.promotionservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CouponDataExport(int schemaVersion,String scope,boolean complete,Instant generatedAt,
        long orderId,Reservation reservation,List<Omission> omissions) {
    @Schema(name="CouponExportReservation")
    public record Reservation(long orderId,String code,BigDecimal subtotal,BigDecimal discountAmount,String state,
            Instant acceptedAt,Instant expiresAt,Instant createdAt,Instant updatedAt) {}
    @Schema(name="CouponExportOmission")
    public record Omission(String area,String reason) {}
}
