package org.demo.customerservice.customer;

public class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException() {
        super("The account confirmation link is invalid or has expired");
    }
}
