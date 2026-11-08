package org.demo.promotionservice.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PriceHistoryResponse(Long productId, String campaignCode, BigDecimal regularPrice,
                                   BigDecimal promotionalPrice, int discountPercent, LocalDate rotationDate,
                                   Instant recordedAt, Long priceRevision, Long layoutRevision, String sourceType) {
}
