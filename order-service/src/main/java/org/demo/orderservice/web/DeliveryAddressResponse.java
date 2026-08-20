package org.demo.orderservice.web;

public record DeliveryAddressResponse(
        String firstName,
        String lastName,
        String street,
        String postalCode,
        String city,
        String country
) {
}
