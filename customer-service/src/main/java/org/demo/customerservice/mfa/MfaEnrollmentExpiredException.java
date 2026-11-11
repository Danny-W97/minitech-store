package org.demo.customerservice.mfa;

public class MfaEnrollmentExpiredException extends RuntimeException {
    public MfaEnrollmentExpiredException() { super("TOTP setup expired; start enrollment again"); }
}
