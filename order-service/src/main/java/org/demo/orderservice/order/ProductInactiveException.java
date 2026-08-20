package org.demo.orderservice.order;

public class ProductInactiveException extends RuntimeException {

    public ProductInactiveException(Long productId) {
        super("Product %d is inactive".formatted(productId));
    }
}
