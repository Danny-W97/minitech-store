package org.demo.customerservice.customer;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String email) {
        super("Customer with email '%s' already exists".formatted(email));
    }
}
