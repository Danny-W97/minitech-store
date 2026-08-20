package org.demo.promotionservice.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CouponValidationRequest(
        @NotBlank @Size(max = 40) String code,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal subtotal
) {}
