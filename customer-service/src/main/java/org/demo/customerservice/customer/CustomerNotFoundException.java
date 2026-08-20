package org.demo.customerservice.customer;

public class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException(Long id) {
        super("Customer %d was not found".formatted(id));
    }
}
