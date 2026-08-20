package org.demo.customerservice.auth;

public record CustomerPrincipal(Long customerId, String email) {
}
