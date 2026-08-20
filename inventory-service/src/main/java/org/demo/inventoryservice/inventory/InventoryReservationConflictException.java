package org.demo.inventoryservice.inventory;

public class InventoryReservationConflictException extends RuntimeException {

    public InventoryReservationConflictException(String message) {
        super(message);
    }
}
