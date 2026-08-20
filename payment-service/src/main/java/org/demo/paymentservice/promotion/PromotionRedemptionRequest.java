package org.demo.paymentservice.promotion;

public record PromotionRedemptionRequest(String code, Long orderId) {
}
