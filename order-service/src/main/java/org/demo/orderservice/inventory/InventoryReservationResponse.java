package org.demo.orderservice.inventory;

import java.time.Instant;

public record InventoryReservationResponse(
        Long reservationId,
        Long orderId,
        String status,
        Instant expiresAt,
        java.util.List<InventoryReservationItemRequest> items
) {
    public InventoryReservationResponse(Long reservationId,Long orderId,String status,Instant expiresAt) {
        this(reservationId,orderId,status,expiresAt,java.util.List.of());
    }
}
