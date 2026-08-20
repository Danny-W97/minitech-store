package org.demo.deliveryservice.delivery;

public class InvalidShipmentStatusException extends RuntimeException {

    public InvalidShipmentStatusException(String status) {
        super("Unsupported shipment status: " + status);
    }
}
