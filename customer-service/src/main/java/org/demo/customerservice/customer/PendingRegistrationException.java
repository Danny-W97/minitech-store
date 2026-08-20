package org.demo.customerservice.customer;

public class PendingRegistrationException extends RuntimeException {

    public PendingRegistrationException(String email) {
        super("Registration for email '%s' is waiting for confirmation".formatted(email));
    }
}
