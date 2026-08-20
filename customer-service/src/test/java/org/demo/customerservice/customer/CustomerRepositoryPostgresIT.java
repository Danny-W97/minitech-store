package org.demo.customerservice.customer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("dev")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CustomerRepositoryPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("customer_db")
            .withUsername("customer_app")
            .withPassword("integration-password");

    @Autowired
    private CustomerRepository repository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void persistsCustomerAndRefreshTokenSchemaAgainstRealPostgres() {
        Customer customer = new Customer(
                "integration@example.com",
                "password-hash",
                "Integration",
                "Tester",
                "Testowa 1",
                "00-001",
                "Warszawa",
                "Polska"
        );
        customer.startVerification("verification-hash", Instant.now().plusSeconds(300));

        Customer saved = repository.saveAndFlush(customer);

        assertThat(saved.getId()).isPositive();
        assertThat(repository.findByEmail("integration@example.com"))
                .get()
                .extracting(Customer::getVerificationTokenHash)
                .isEqualTo("verification-hash");
    }
}
