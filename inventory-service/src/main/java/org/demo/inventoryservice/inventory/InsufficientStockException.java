package org.demo.inventoryservice.inventory;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long productId, Integer available, Integer requested) {
        super("Insufficient stock for product %d: available=%d, requested=%d"
                .formatted(productId, available, requested));
    }
}
