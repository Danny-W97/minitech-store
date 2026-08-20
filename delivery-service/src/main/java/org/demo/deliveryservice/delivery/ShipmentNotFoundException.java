package org.demo.deliveryservice.delivery;

public class ShipmentNotFoundException extends RuntimeException {

    public ShipmentNotFoundException(Long orderId) {
        super("Shipment not found for order: " + orderId);
    }
}
