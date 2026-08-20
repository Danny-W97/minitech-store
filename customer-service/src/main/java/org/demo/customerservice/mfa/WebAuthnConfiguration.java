package org.demo.customerservice.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class WebAuthnConfiguration {

    @Bean
    UserCredentialRepository userCredentialRepository(JdbcOperations jdbcOperations) {
        return new JdbcUserCredentialRepository(jdbcOperations);
    }

    @Bean
    Webauthn4JRelyingPartyOperations webAuthnOperations(
            CustomerWebAuthnUserEntityRepository userRepository,
            UserCredentialRepository credentialRepository,
            @Value("${customer.webauthn.rp-id:localhost}") String rpId,
            @Value("${customer.webauthn.allowed-origins:http://localhost:18070}") String allowedOrigins
    ) {
        PublicKeyCredentialRpEntity rp = PublicKeyCredentialRpEntity.builder()
                .id(rpId)
                .name("MiniTech Store")
                .build();
        Set<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        return new Webauthn4JRelyingPartyOperations(userRepository, credentialRepository, rp, origins);
    }
}
