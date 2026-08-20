package org.demo.orderservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** A single product line submitted as part of one customer order. */
public record CreateOrderItemRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity
) {
}
