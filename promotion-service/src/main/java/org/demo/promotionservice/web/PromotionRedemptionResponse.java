package org.demo.promotionservice.web;

public record PromotionRedemptionResponse(
        String code,
        Long orderId,
        int redeemedCount,
        Integer maxRedemptions,
        boolean redeemed
) {
}
