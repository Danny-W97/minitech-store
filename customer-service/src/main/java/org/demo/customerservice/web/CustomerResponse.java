package org.demo.customerservice.web;

import org.demo.customerservice.customer.Customer;

import java.time.Instant;

public record CustomerResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String street,
        String postalCode,
        String city,
        String country,
        String phoneNumber,
        Instant createdAt,
        Instant updatedAt
) {
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(), customer.getEmail(), customer.getFirstName(), customer.getLastName(),
                customer.getStreet(), customer.getPostalCode(), customer.getCity(), customer.getCountry(),
                customer.getPhoneNumber(),
                customer.getCreatedAt(), customer.getUpdatedAt()
        );
    }
}
