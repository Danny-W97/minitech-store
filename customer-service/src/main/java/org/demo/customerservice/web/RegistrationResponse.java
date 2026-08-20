package org.demo.customerservice.web;

import java.time.Instant;

public record RegistrationResponse(
        String email,
        boolean verificationRequired,
        Instant verificationExpiresAt,
        String message
) {
}
