package org.demo.productservice.product;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("Product with SKU %s already exists".formatted(sku));
    }
}
