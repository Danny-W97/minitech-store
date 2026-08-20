package org.demo.orderservice.promotion;

import java.math.BigDecimal;

public record CouponValidationResponse(
        String code, boolean valid, String message, int discountPercent,
        BigDecimal discountAmount, BigDecimal subtotal, BigDecimal totalAfterDiscount
) {}
