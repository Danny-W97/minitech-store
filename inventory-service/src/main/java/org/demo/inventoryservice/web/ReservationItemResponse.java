package org.demo.inventoryservice.web;

public record ReservationItemResponse(
        Long productId,
        Integer quantity
) {
}
