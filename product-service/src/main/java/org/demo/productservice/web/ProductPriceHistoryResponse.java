package org.demo.productservice.web;

import org.demo.productservice.product.ProductPriceHistoryEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ProductPriceHistoryResponse(Long productId, String campaignCode, BigDecimal regularPrice,
        BigDecimal promotionalPrice, int discountPercent, LocalDate rotationDate, Instant recordedAt,
        long priceRevision, long layoutRevision, String sourceType) {
    public static ProductPriceHistoryResponse from(ProductPriceHistoryEvent event) {
        return new ProductPriceHistoryResponse(event.getProductId(), event.getCampaignCode(), event.getRegularPrice(),
                event.getEffectivePrice(), event.getDiscountPercent(), event.getRotationDate(), event.getRecordedAt(),
                event.getPriceRevision(), event.getLayoutRevision(), event.getSourceType());
    }
}
