package org.demo.orderservice.order;

import org.demo.orderservice.web.DeliveryAddressRequest;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderSagaPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_db")
            .withUsername("order_app")
            .withPassword("integration-password");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaRepository sagaRepository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void persistsSagaStateAndNullableRecoveryTimestampAgainstRealPostgres() {
        Order order = new Order(
                new BigDecimal("19.99"),
                null,
                "integration@example.com",
                new DeliveryAddressRequest(
                        "Integration",
                        "Tester",
                        "Testowa 1",
                        "00-001",
                        "Warszawa",
                        "Polska"
                ),
                "integration-saga"
        );
        order.addItem(101L, "SKU-101", "Test product", 1,
                new BigDecimal("19.99"), new BigDecimal("19.99"));

        Order savedOrder = orderRepository.saveAndFlush(order);
        OrderSaga saga = sagaRepository.saveAndFlush(new OrderSaga(savedOrder.getId()));
        saga.waitingForPayment();
        sagaRepository.saveAndFlush(saga);

        assertThat(sagaRepository.findByOrderId(savedOrder.getId()))
                .get()
                .satisfies(savedSaga -> {
                    assertThat(savedSaga.getState()).isEqualTo(OrderSagaState.WAITING_FOR_PAYMENT);
                    assertThat(savedSaga.getNextAttemptAt()).isNull();
                });
    }
}
