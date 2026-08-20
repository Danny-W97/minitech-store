package org.demo.customerservice.auth;

import java.time.Instant;

public record JwtClaims(Long customerId, String email, Instant expiresAt) {
}
