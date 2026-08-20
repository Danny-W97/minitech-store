package org.demo.inventoryservice;

import org.demo.inventoryservice.inventory.InventoryItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryItemTests {

    @Test
    void confirmationMovesReservedQuantityToSoldQuantity() {
        InventoryItem item = new InventoryItem(101L, 10);

        item.reserve(3);
        item.confirm(3);

        assertEquals(7, item.getAvailableQuantity());
        assertEquals(0, item.getReservedQuantity());
        assertEquals(3, item.getSoldQuantity());
    }
}
