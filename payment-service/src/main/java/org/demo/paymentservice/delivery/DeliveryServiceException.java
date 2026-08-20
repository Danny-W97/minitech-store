package org.demo.paymentservice.delivery;

public class DeliveryServiceException extends RuntimeException {

    public DeliveryServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
