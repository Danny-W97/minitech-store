package org.demo.orderservice.order;

public class AccountRequiredException extends RuntimeException {
    public AccountRequiredException() {
        super("Orders with a product subtotal above PLN 1000 require a customer account");
    }
}
