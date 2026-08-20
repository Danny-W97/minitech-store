package org.demo.deliveryservice.delivery;

public class DeliveryMethodNotFoundException extends RuntimeException {

    public DeliveryMethodNotFoundException(String code) {
        super("Active delivery method not found: " + code);
    }
}
