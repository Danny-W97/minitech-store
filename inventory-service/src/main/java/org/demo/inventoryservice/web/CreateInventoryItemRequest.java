package org.demo.inventoryservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateInventoryItemRequest(
        @NotNull @Positive Long productId,
        @NotNull @PositiveOrZero Integer availableQuantity
) {
}
