package org.demo.inventoryservice.inventory;

public class InventoryReservationNotFoundException extends RuntimeException {

    public InventoryReservationNotFoundException(Long reservationId) {
        super("Inventory reservation %d was not found".formatted(reservationId));
    }
}
