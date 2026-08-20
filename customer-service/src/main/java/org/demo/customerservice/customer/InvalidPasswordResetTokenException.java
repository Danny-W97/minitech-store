package org.demo.customerservice.customer;

public class InvalidPasswordResetTokenException extends RuntimeException {
    public InvalidPasswordResetTokenException() {
        super("The password reset link is invalid, expired or already used");
    }
}
