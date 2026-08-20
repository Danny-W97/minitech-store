package org.demo.paymentservice.payment;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long paymentId) {
        super("Payment %d was not found".formatted(paymentId));
    }
}
