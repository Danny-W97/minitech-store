package org.demo.productservice.product;

public class NoProductChangesException extends RuntimeException {

    public NoProductChangesException() {
        super("At least one product field must be provided");
    }
}
