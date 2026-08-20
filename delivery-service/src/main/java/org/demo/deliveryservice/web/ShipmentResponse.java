package org.demo.deliveryservice.web;

import java.time.Instant;

public record ShipmentResponse(
        Long orderId,
        String trackingNumber,
        String carrier,
        String methodCode,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
