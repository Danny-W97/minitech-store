package org.demo.promotionservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record RedemptionDataExportPage(int schemaVersion,String scope,boolean complete,Instant generatedAt,
        long orderId,List<Redemption> items,boolean hasMore,String nextCursor,List<CouponDataExport.Omission> omissions) {
    @Schema(name="CouponExportRedemption")
    public record Redemption(long redemptionId,long orderId,String code,Instant redeemedAt) {}
}
