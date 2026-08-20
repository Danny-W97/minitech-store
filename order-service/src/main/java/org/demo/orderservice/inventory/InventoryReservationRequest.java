package org.demo.orderservice.inventory;

import java.util.List;

public record InventoryReservationRequest(
        Long orderId,
        List<InventoryReservationItemRequest> items
) {
}
