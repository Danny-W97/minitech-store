package org.demo.inventoryservice.inventory;

public class InventoryItemNotFoundException extends RuntimeException {

    public InventoryItemNotFoundException(Long productId) {
        super("Inventory item for product %d was not found".formatted(productId));
    }
}
