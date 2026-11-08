package org.demo.orderservice.order;

import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@Import(OrderCreationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderCreationTransactionTest {
    @Autowired private OrderCreationService creation;
    @Autowired private OrderRepository orders;
    @MockitoSpyBean private OrderSagaRepository sagas;

    @BeforeEach
    void clearData() {
        sagas.deleteAll();
        orders.deleteAll();
    }

    private Order order(String key) {
        Order order = new Order(new BigDecimal("100.00"), null, "buyer@example.com",
                new DeliveryAddressRequest("Jan", "Kowalski", "Testowa 1", "00-001", "Warszawa", "Polska", null), key);
        order.addItem(101L, "SKU-101", "Product", 1, new BigDecimal("100.00"), new BigDecimal("100.00"));
        return order;
    }

    @Test
    void commitsOrderAndRecoverableSagaTogether() {
        Order saved = creation.create(order("atomic-success"));
        assertThat(orders.findById(saved.getId())).isPresent();
        assertThat(sagas.findByOrderId(saved.getId())).get()
                .satisfies(saga -> assertThat(saga.getState()).isEqualTo(OrderSagaState.INVENTORY_RESERVATION_PENDING));
    }

    @Test
    void rollsBackOrderWhenSagaCannotBeWritten() {
        doThrow(new IllegalStateException("Simulated saga write failure")).when(sagas).save(any(OrderSaga.class));
        assertThatThrownBy(() -> creation.create(order("atomic-rollback")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orders.findByIdempotencyKey("atomic-rollback")).isEmpty();
        assertThat(orders.count()).isZero();
        assertThat(sagas.count()).isZero();
    }
}
