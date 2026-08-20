package org.demo.orderservice.web;

import jakarta.validation.constraints.NotNull;
import org.demo.orderservice.order.OrderStatus;

public record ChangeOrderStatusRequest(
        @NotNull OrderStatus status
) {
}
