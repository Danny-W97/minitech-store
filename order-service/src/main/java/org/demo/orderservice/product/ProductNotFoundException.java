package org.demo.orderservice.product;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("Product %d was not found".formatted(id));
    }
}
