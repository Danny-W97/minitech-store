package org.demo.paymentservice.order;

public record DeliveryAddressResponse(
        String firstName,
        String lastName,
        String street,
        String postalCode,
        String city,
        String country
) {
}
