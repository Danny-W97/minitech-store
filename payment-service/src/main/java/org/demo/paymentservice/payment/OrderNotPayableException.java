package org.demo.paymentservice.payment;

public class OrderNotPayableException extends RuntimeException {

    public OrderNotPayableException(Long orderId, String status) {
        super("Order %d cannot be paid in status %s".formatted(orderId, status));
    }
}
