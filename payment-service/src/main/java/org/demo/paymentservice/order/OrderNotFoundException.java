package org.demo.paymentservice.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long orderId) {
        super("Order %d was not found".formatted(orderId));
    }
}
