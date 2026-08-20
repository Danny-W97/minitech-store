package org.demo.promotionservice.web;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PromotionEvaluationResponse(Long productId, BigDecimal price, BigDecimal regularPrice,
                                          int discountPercent, boolean promotional, boolean newProduct,
                                          LocalDate rotationDate, String campaignCode) {
}
