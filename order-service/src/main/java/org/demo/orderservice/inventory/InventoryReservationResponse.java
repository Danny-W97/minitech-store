package org.demo.orderservice.inventory;

import java.time.Instant;

public record InventoryReservationResponse(
        Long reservationId,
        Long orderId,
        String status,
        Instant expiresAt
) {
}
