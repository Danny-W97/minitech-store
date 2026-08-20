package org.demo.orderservice.promotion;

import java.math.BigDecimal;

public record CouponValidationRequest(String code, BigDecimal subtotal) {}
