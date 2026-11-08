package org.demo.orderservice.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.demo.orderservice.order.*;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.demo.platformsecurity.PersistentQueueMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-metrics-consistency-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "order.saga.recovery-enabled=false"
})
class OrderBusinessMetricsAndConsistencyTest {

    @Autowired
    private PersistentQueueMetrics businessQueueMetrics;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaRepository sagaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private OrderReconciliationService reconciliationService;

    @BeforeEach
    void cleanUp() {
        sagaRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private double count(String queue) {
        return meterRegistry.get("platform.business.queue.count")
                .tag("queue", queue)
                .gauge()
                .value();
    }

    private double oldestAge(String queue) {
        return meterRegistry.get("platform.business.queue.oldest.age.seconds")
                .tag("queue", queue)
                .gauge()
                .value();
    }

    private double up(String queue) {
        return meterRegistry.get("platform.business.collector.up")
                .tag("queue", queue)
                .gauge()
                .value();
    }

    @Test
    void cleanStateExposesZeroMetricsAndHealthyCollector() {
        businessQueueMetrics.collect();

        assertThat(count("order_saga")).isZero();
        assertThat(count("order_reconciliation")).isZero();
        assertThat(oldestAge("order_saga")).isZero();
        assertThat(oldestAge("order_reconciliation")).isZero();
        assertThat(up("order_saga")).isEqualTo(1.0);
        assertThat(up("order_reconciliation")).isEqualTo(1.0);
    }

    @Test
    void pendingOrderSagasAreTrackedInMetrics() {
        Order order = new Order(BigDecimal.TEN, 1L, "customer@example.test",
                new DeliveryAddressRequest("Jan", "Kowalski", "Prosta 1", "00-001", "Warszawa", "PL", null));
        order.addItem(10L, "SKU-1", "Product 1", 1, BigDecimal.TEN, BigDecimal.TEN);
        orderRepository.saveAndFlush(order);

        OrderSaga saga = new OrderSaga(order.getId());
        sagaRepository.saveAndFlush(saga);

        businessQueueMetrics.collect();

        assertThat(count("order_saga")).isEqualTo(1.0);
        assertThat(count("order_reconciliation")).isZero();
        assertThat(up("order_saga")).isEqualTo(1.0);
    }

    @Test
    void crossDatabaseDivergenceFlagsReconciliationQueueAndPublishesDeadLetterMetrics() {
        // Create an order that was marked PAID but has no saga (e.g. lost saga or cross-database split-brain)
        Order order = new Order(BigDecimal.TEN, 2L, "customer@example.test",
                new DeliveryAddressRequest("Anna", "Nowak", "Dluga 2", "00-002", "Warszawa", "PL", null));
        order.addItem(11L, "SKU-2", "Product 2", 1, BigDecimal.TEN, BigDecimal.TEN);
        order.changeStatus(OrderStatus.PAID);
        order = orderRepository.saveAndFlush(order);

        // Run bounded missing scan which detects unattached paid order and flags RECONCILIATION_REQUIRED
        reconciliationService.scanMissing();

        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        OrderSaga updatedSaga = sagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(updatedOrder.isLifecycleReviewRequired()).isTrue();
        assertThat(updatedSaga.getState()).isEqualTo(OrderSagaState.RECONCILIATION_REQUIRED);

        // Set created_at into the past to verify oldest age computation
        Instant twoHoursAgo = Instant.now().minusSeconds(7200);
        jdbcTemplate.update("UPDATE order_sagas SET created_at = ? WHERE order_id = ?",
                Timestamp.from(twoHoursAgo), order.getId());

        businessQueueMetrics.collect();

        assertThat(count("order_reconciliation")).isEqualTo(1.0);
        assertThat(oldestAge("order_reconciliation")).isGreaterThanOrEqualTo(7100);
        assertThat(up("order_reconciliation")).isEqualTo(1.0);
    }

    @Test
    void resolvingReconciliationReturnsMetricToZero() {
        Order order = new Order(BigDecimal.TEN, 3L, "customer@example.test",
                new DeliveryAddressRequest("Piotr", "Wisniewski", "Krotka 3", "00-003", "Krakow", "PL", null));
        order.addItem(12L, "SKU-3", "Product 3", 1, BigDecimal.TEN, BigDecimal.TEN);
        order.changeStatus(OrderStatus.CONFIRMED);
        order = orderRepository.saveAndFlush(order);

        OrderSaga saga = new OrderSaga(order.getId());
        saga.reconciliationRequired("Divergence under manual operator investigation");
        sagaRepository.saveAndFlush(saga);

        businessQueueMetrics.collect();
        assertThat(count("order_reconciliation")).isEqualTo(1.0);

        // Operator resolves the saga
        saga.completed();
        sagaRepository.saveAndFlush(saga);

        businessQueueMetrics.collect();
        assertThat(count("order_reconciliation")).isZero();
    }
}
