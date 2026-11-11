package org.demo.orderservice.order;

import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import({DeferredFulfillmentService.class,OrderSagaStateStore.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class DeferredFulfillmentTest {
    @Autowired OrderRepository orders;
    @Autowired OrderSagaRepository sagas;
    @Autowired DeferredFulfillmentService fulfillment;
    @Autowired OrderSagaStateStore sagaStates;
    @BeforeEach void clear() { sagas.deleteAll();orders.deleteAll(); }
    private Long seed(String method) {
        var order=new Order(BigDecimal.TEN,17L,"owner@example.test",new DeliveryAddressRequest("Jan","Test","Street","00-001","City","PL",null),method,BigDecimal.ZERO,"key-"+java.util.UUID.randomUUID());
        order.addItem(1L,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN);order.assignInventoryReservation(42L);order.changeStatus(OrderStatus.CONFIRMED);
        order=orders.saveAndFlush(order);var saga=new OrderSaga(order.getId());saga.waitingForPayment();sagas.saveAndFlush(saga);return order.getId();
    }
    @Test void fulfillmentAdvancesWhileCollectionIsDueAndSettlementDoesNotRegressIt() {
        Long id=seed("STANDARD_COURIER");
        assertThat(fulfillment.accept(id,91L,"CASH_ON_DELIVERY",BigDecimal.TEN,"PLN").paymentState()).isEqualTo("COLLECTION_DUE");
        var shipped=fulfillment.transition(id,OrderStatus.SHIPPED);assertThat(shipped.paymentState()).isEqualTo("COLLECTION_DUE");
        fulfillment.transition(id,OrderStatus.COMPLETED);
        var collected=fulfillment.collect(id,91L,"RECEIPT-91",BigDecimal.TEN,"PLN");
        assertThat(collected.status()).isEqualTo(OrderStatus.COMPLETED);assertThat(collected.paymentState()).isEqualTo("PAID");
        assertThat(fulfillment.collect(id,91L,"RECEIPT-91",BigDecimal.TEN,"PLN").paymentState()).isEqualTo("PAID");
        assertThatThrownBy(() -> fulfillment.collect(id,91L,"DIFFERENT",BigDecimal.TEN,"PLN")).hasMessageContaining("409");
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(OrderSagaState.COMPLETED);
    }
    @Test void pickupCanBeHandedOverWithoutPretendingTheCashWasAlreadyPaid() {
        Long id=seed("STORE_PICKUP");fulfillment.accept(id,92L,"CASH_AT_PICKUP",BigDecimal.TEN,"PLN");
        assertThat(fulfillment.transition(id,OrderStatus.COMPLETED).paymentState()).isEqualTo("COLLECTION_DUE");
        assertThatThrownBy(() -> fulfillment.transition(id,OrderStatus.PAID)).hasMessageContaining("409");
        assertThatThrownBy(() -> sagaStates.claim(id,true)).hasMessageContaining("409");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }
    @Test void mismatchedMethodMoneyAndPaymentIdentityCannotChangeAcceptedFulfillment() {
        Long id=seed("STANDARD_COURIER");
        assertThatThrownBy(() -> fulfillment.accept(id,93L,"CASH_AT_PICKUP",BigDecimal.TEN,"PLN")).hasMessageContaining("409");
        assertThatThrownBy(() -> fulfillment.accept(id,93L,"CASH_ON_DELIVERY",BigDecimal.ONE,"PLN")).hasMessageContaining("409");
        fulfillment.accept(id,93L,"CASH_ON_DELIVERY",BigDecimal.TEN,"PLN");
        assertThatThrownBy(() -> fulfillment.accept(id,94L,"CASH_ON_DELIVERY",BigDecimal.TEN,"PLN")).hasMessageContaining("409");
        assertThatThrownBy(() -> fulfillment.collect(id,94L,"RECEIPT-94",BigDecimal.TEN,"PLN")).hasMessageContaining("409");
        assertThatThrownBy(() -> sagaStates.claim(id,true)).hasMessageContaining("409");
    }
    @Test void twoConcurrentAcceptancesBindOnlyOnePayment() throws Exception {
        Long id=seed("STANDARD_COURIER");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> { try { fulfillment.accept(id,101L,"CASH_ON_DELIVERY",BigDecimal.TEN,"PLN");return 200; } catch(org.springframework.web.server.ResponseStatusException expected) { return expected.getStatusCode().value(); } });
            var second=pool.submit(() -> { try { fulfillment.accept(id,102L,"CASH_ON_DELIVERY",BigDecimal.TEN,"PLN");return 200; } catch(org.springframework.web.server.ResponseStatusException expected) { return expected.getStatusCode().value(); } });
            assertThat(java.util.List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
}
