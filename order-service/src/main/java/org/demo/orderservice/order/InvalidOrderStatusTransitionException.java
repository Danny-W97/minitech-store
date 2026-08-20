package org.demo.orderservice.order;

public class InvalidOrderStatusTransitionException extends RuntimeException {

    public InvalidOrderStatusTransitionException(OrderStatus current, OrderStatus requested) {
        super("Cannot change order status from %s to %s".formatted(current, requested));
    }
}
