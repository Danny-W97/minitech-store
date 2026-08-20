package org.demo.promotionservice.web;

import java.time.LocalDate;
import java.math.BigDecimal;

public record PromotionCodeResponse(
        String code, String description, int discountPercent, Integer maxRedemptions,
        int redeemedCount, boolean active, LocalDate validFrom, LocalDate validTo,
        BigDecimal minimumOrderValue, BigDecimal maximumDiscountAmount
) {}
