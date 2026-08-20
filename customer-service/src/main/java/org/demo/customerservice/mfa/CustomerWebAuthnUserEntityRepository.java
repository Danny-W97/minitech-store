package org.demo.customerservice.mfa;

import org.demo.customerservice.customer.Customer;
import org.demo.customerservice.customer.CustomerRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class CustomerWebAuthnUserEntityRepository implements PublicKeyCredentialUserEntityRepository {

    private final CustomerRepository customerRepository;

    public CustomerWebAuthnUserEntityRepository(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public PublicKeyCredentialUserEntity findById(Bytes id) {
        return customerRepository.findById(parseId(id.getBytes()))
                .map(this::toEntity)
                .orElse(null);
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        return customerRepository.findByEmail(username.trim().toLowerCase())
                .map(this::toEntity)
                .orElse(null);
    }

    @Override
    public void save(PublicKeyCredentialUserEntity userEntity) {
        // Customer Service is the source of truth for the user entity.
    }

    @Override
    public void delete(Bytes id) {
        // Customer deletion cascades credentials through the Customer DB schema.
    }

    public static Bytes userId(Long customerId) {
        return new Bytes(String.valueOf(customerId).getBytes(StandardCharsets.UTF_8));
    }

    private PublicKeyCredentialUserEntity toEntity(Customer customer) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .id(userId(customer.getId()))
                .name(customer.getEmail())
                .displayName(customer.getFirstName() + " " + customer.getLastName())
                .build();
    }

    private Long parseId(byte[] bytes) {
        return Long.valueOf(new String(bytes, StandardCharsets.UTF_8));
    }
}
