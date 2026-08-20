package org.demo.customerservice.mfa;

public class MfaAlreadyEnabledException extends RuntimeException {
    public MfaAlreadyEnabledException() {
        super("MFA is already enabled");
    }
}
