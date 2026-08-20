package org.demo.customerservice.auth;

public record MfaLoginChallenge(
        boolean mfaRequired,
        String challengeId,
        long expiresIn,
        String method
) {
}
