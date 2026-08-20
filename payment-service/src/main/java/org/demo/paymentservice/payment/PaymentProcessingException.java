package org.demo.paymentservice.payment;

public class PaymentProcessingException extends RuntimeException {

    public PaymentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
