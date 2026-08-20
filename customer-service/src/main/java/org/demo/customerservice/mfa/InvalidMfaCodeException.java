package org.demo.customerservice.mfa;

public class InvalidMfaCodeException extends RuntimeException {
    public InvalidMfaCodeException() {
        super("Invalid or already used MFA code");
    }
}
