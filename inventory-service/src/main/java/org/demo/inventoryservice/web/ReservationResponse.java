package org.demo.inventoryservice.web;

import org.demo.inventoryservice.inventory.ReservationStatus;

import java.util.List;
import java.time.Instant;

public record ReservationResponse(
        Long reservationId,
        Long orderId,
        ReservationStatus status,
        Instant expiresAt,
        List<ReservationItemResponse> items
) {
}
