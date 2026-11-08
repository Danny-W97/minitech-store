package org.demo.orderservice.delivery;

public class InvalidDeliverySelectionException extends RuntimeException {
    public InvalidDeliverySelectionException(String message) {
        super(message);
    }
}
