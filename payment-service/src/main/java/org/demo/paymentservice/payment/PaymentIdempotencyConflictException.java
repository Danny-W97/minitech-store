package org.demo.paymentservice.payment;

public class PaymentIdempotencyConflictException extends RuntimeException {

    public PaymentIdempotencyConflictException(Long orderId) {
        super("Payment idempotency key does not match the existing payment for order %d".formatted(orderId));
    }
}
