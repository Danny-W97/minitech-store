package org.demo.orderservice.delivery;

public class DeliveryServiceException extends RuntimeException {
    public DeliveryServiceException(String message) {
        super(message);
    }

    public DeliveryServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
