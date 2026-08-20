package org.demo.customerservice.auth;

public class MfaChallengeExpiredException extends RuntimeException {
    public MfaChallengeExpiredException() {
        super("MFA challenge is invalid or expired");
    }
}
