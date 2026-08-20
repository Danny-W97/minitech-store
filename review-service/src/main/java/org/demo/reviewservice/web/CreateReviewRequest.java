package org.demo.reviewservice.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateReviewRequest(
        @NotNull @Positive Long customerId,
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long productId,
        @NotNull @Min(1) @Max(5) Integer rating,
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 2000) String content
) {}
