package org.demo.orderservice.web;

import org.demo.orderservice.order.OrderSagaState;

import java.time.Instant;

public record OrderSagaResponse(
        Long orderId,
        OrderSagaState state,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
