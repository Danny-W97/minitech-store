package org.demo.orderservice.inventory;

public record InventoryReservationItemRequest(
        Long productId,
        Integer quantity
) {
}
