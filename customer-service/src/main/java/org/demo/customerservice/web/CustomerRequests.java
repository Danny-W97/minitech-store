package org.demo.customerservice.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CustomerRequests {
    private CustomerRequests() {
    }

    public record Register(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 200) String street,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 120) String city,
            @NotBlank @Size(max = 120) String country
    ) {
    }

    public record Login(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank String password
    ) {
    }

    public record ResendVerification(
            @NotBlank @Email @Size(max = 255) String email
    ) {
    }

    public record PasswordResetRequest(
            @NotBlank @Email @Size(max = 255) String email
    ) {
    }

    public record PasswordResetConfirm(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 100) String password
    ) {
    }

    public record Address(
            @NotBlank @Size(max = 200) String street,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 120) String city,
            @NotBlank @Size(max = 120) String country
    ) {
    }

    public record Profile(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 200) String street,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 120) String city,
            @NotBlank @Size(max = 120) String country,
            @Size(max = 30) String phoneNumber
    ) {
    }
}
