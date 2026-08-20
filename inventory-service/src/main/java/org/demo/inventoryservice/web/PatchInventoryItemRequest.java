package org.demo.inventoryservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PatchInventoryItemRequest(
        @NotNull @PositiveOrZero Integer availableQuantity
) {
}
