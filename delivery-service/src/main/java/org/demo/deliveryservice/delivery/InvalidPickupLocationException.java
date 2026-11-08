package org.demo.deliveryservice.delivery;

public class InvalidPickupLocationException extends RuntimeException {
    public InvalidPickupLocationException(String message) { super(message); }
}
