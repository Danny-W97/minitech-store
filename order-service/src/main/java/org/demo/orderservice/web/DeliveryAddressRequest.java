package org.demo.orderservice.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeliveryAddressRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 200) String street,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(max = 120) String city,
        @NotBlank @Size(max = 120) String country,
        @Size(max = 30) String phone
) {
}
