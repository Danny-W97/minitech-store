package org.demo.orderservice.order;

import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:stripe-order;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","order.saga.recovery-enabled=false"})
class StripeRefundHoldTest {
    @Autowired OrderRefundService refunds;
    @Autowired OrderRepository orders;
    @Autowired OrderSagaRepository sagas;
    @Autowired DeferredFulfillmentService fulfillment;
    @Autowired OrderSagaStateStore lifecycle;
    @Autowired OrderReconciliationStore reconciliation;
    @BeforeEach void clear() { sagas.deleteAll();orders.deleteAll(); }
    private Long seed(OrderStatus status) {
        var order=new Order(BigDecimal.TEN,17L,"owner@example.test",new DeliveryAddressRequest("Jan","Test","Street","00-001","City","PL",null));
        order.addItem(1L,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN);order.changeStatus(status);order.assignInventoryReservation(42L);
        order=orders.saveAndFlush(order);var saga=new OrderSaga(order.getId());
        if(status==OrderStatus.CONFIRMED) saga.waitingForPayment();else saga.completed();
        sagas.saveAndFlush(saga);return order.getId();
    }
    private OrderRefundService.Command command(boolean cancel) { return new OrderRefundService.Command(901L,BigDecimal.TEN,"PLN",cancel); }
    @Test void financialHoldIsDurableIdempotentAndBlocksShippingAndOrdinaryCancellation() {
        var id=seed(OrderStatus.PAID);assertThat(refunds.begin(id,command(false)).paymentState()).isEqualTo("REFUND_PENDING");
        assertThat(refunds.begin(id,command(false)).lifecycleReviewRequired()).isTrue();
        assertThatThrownBy(() -> fulfillment.transition(id,OrderStatus.SHIPPED)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> lifecycle.claim(id,true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> reconciliation.claim(id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");
        assertThatThrownBy(() -> orders.findById(id).orElseThrow().clearLifecycleReview()).isInstanceOf(IllegalStateException.class);
        var completed=refunds.complete(id,command(false));
        assertThat(completed.paymentState()).isEqualTo("REFUNDED");assertThat(completed.status()).isEqualTo(OrderStatus.PAID);
        assertThat(completed.lifecycleReviewRequired()).isTrue();
    }
    @Test void automaticCompensationMayCancelOnlyAfterMatchingHold() {
        var id=seed(OrderStatus.CONFIRMED);
        assertThatThrownBy(() -> refunds.complete(id,command(true))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        refunds.begin(id,command(false));var result=refunds.complete(id,command(true));
        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);assertThat(result.paymentState()).isEqualTo("REFUNDED");
        assertThat(refunds.complete(id,command(true)).status()).isEqualTo(OrderStatus.CANCELLED);
    }
    @Test void shippedOrdersAndConflictingPaymentsCannotAcquireTheHold() {
        var shipped=seed(OrderStatus.SHIPPED);
        assertThatThrownBy(() -> refunds.begin(shipped,command(false))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var paid=seed(OrderStatus.PAID);refunds.begin(paid,command(false));
        assertThatThrownBy(() -> refunds.begin(paid,new OrderRefundService.Command(902L,BigDecimal.TEN,"PLN",false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void shippingAndRefundHaveExactlyOneWinner() throws Exception {
        var id=seed(OrderStatus.PAID);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ship=pool.submit(() -> { start.await();try { fulfillment.transition(id,OrderStatus.SHIPPED);return true; }catch(RuntimeException refused){return false;} });
            var refund=pool.submit(() -> { start.await();try { refunds.begin(id,command(false));return true; }catch(RuntimeException refused){return false;} });
            start.countDown();assertThat((ship.get(10,TimeUnit.SECONDS) ? 1 : 0)+(refund.get(10,TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
    }
    @Test void externalRefundCanRecordMoneyAlreadyReturnedForAShippedOrderWithoutReversingShipping() {
        var id=seed(OrderStatus.SHIPPED);
        var external=new OrderRefundService.Command(901L,BigDecimal.TEN,"PLN",false,true);
        refunds.begin(id,external);var result=refunds.complete(id,external);
        assertThat(result.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(result.paymentState()).isEqualTo("REFUNDED");assertThat(result.lifecycleReviewRequired()).isTrue();
    }
}
