package org.demo.promotionservice.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PromotionRedemptionRequest(
        @NotBlank String code,
        @NotNull @Positive Long orderId
) {
}
