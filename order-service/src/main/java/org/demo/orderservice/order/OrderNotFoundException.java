package org.demo.orderservice.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long id) {
        super("Order %d was not found".formatted(id));
    }
}
