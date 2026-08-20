package org.demo.customerservice.auth;

import org.demo.customerservice.web.CustomerResponse;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        long refreshTokenExpiresIn,
        CustomerResponse customer
) {
}
