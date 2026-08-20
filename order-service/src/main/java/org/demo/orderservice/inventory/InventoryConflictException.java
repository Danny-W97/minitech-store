package org.demo.orderservice.inventory;

public class InventoryConflictException extends RuntimeException {

    public InventoryConflictException(String message) {
        super(message);
    }
}
