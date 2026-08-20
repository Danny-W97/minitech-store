package org.demo.paymentservice.billing;

public class BillingServiceException extends RuntimeException {
    public BillingServiceException(String message, Throwable cause) { super(message, cause); }
}
