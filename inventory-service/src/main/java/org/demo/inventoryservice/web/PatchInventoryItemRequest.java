package org.demo.inventoryservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PatchInventoryItemRequest(
        @NotNull @PositiveOrZero Integer availableQuantity,
        @NotNull @PositiveOrZero Long expectedVersion,
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String operationId
) {
}
