package org.demo.customerservice.auth;

public record AuthSession(AuthResponse response, String rawRefreshToken) {
}
