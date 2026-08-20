package org.demo.inventoryservice.web;

import java.time.Instant;

public record InventoryItemResponse(
        Long productId,
        Integer availableQuantity,
        Integer reservedQuantity,
        Integer soldQuantity,
        Instant createdAt,
        Instant updatedAt
) {
}
