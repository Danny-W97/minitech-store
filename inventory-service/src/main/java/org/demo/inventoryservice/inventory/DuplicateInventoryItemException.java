package org.demo.inventoryservice.inventory;

public class DuplicateInventoryItemException extends RuntimeException {

    public DuplicateInventoryItemException(Long productId) {
        super("Inventory item for product %d already exists".formatted(productId));
    }
}
