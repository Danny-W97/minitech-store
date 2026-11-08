package org.demo.orderservice.order;

public class OrderIdempotencyException extends RuntimeException {
    private final String code;
    private final Long orderId;
    public OrderIdempotencyException(String code,Long orderId) {
        super(code);this.code=code;this.orderId=orderId;
    }
    public String code() { return code; }
    public Long orderId() { return orderId; }
}
