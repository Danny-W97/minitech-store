package org.demo.promotionservice.web;

import java.math.BigDecimal;

public record PromotionEvaluationRequest(Long productId, BigDecimal basePrice, int catalogIndex, int catalogSize) {
}
