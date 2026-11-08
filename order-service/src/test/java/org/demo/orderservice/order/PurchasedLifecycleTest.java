package org.demo.orderservice.order;

import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:purchased-lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","order.saga.recovery-enabled=false"})
class PurchasedLifecycleTest {
    @Autowired OrderRepository orders;
    @Autowired OrderSagaRepository sagas;
    @Autowired OrderShipmentService shipments;
    @BeforeEach void clear() { sagas.deleteAll();orders.deleteAll(); }
    private Order seed(OrderStatus status,long product,String method,boolean deferred) {
        var order=new Order(BigDecimal.TEN,17L,"owner@example.test",new DeliveryAddressRequest("Jan","Test","Street","00-001","City","PL",null),
                method,BigDecimal.ZERO,java.util.UUID.randomUUID().toString(),null,BigDecimal.ZERO,"STORE_PICKUP".equals(method)?"STORE_PICKUP":"HOME_DELIVERY",null,null,null);
        order.addItem(product,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN);order.assignInventoryReservation(42L);order.changeStatus(status);
        if(deferred) order.acceptDeferred(91L,"CASH_ON_DELIVERY");
        order=orders.saveAndFlush(order);var saga=new OrderSaga(order.getId());
        if(deferred || status==OrderStatus.CONFIRMED) saga.waitingForPayment();else saga.completed();
        sagas.saveAndFlush(saga);return order;
    }
    private OrderShipmentService.Command command(long revision,String status) { return new OrderShipmentService.Command("MT-TEST-0001","STANDARD_COURIER",revision,status); }
    @Test void purchasesRemainInRecommendationsAndBestsellersAfterShippingAndCompletion() {
        long expected=0;
        for(var status:List.of(OrderStatus.PAID,OrderStatus.SHIPPED,OrderStatus.COMPLETED)) {
            var order=seed(status,1,"STANDARD_COURIER",false);
            assertThat(orders.findPurchasedIds(17L,List.of(1L))).containsExactly(1L);
            assertThat(orders.findPurchasedIds(18L,List.of(1L))).isEmpty();
            assertThat(orders.findBestSellingProductIds(org.springframework.data.domain.PageRequest.of(0,10)).getFirst()[1]).isEqualTo(++expected);
            assertThat(orders.findById(order.getId()).orElseThrow().getPaymentState()).isEqualTo("PAID");
        }
    }
    @Test void cancelledRefundedUnpaidAndUnsettledDeferredOrdersDoNotCountAsPaidPurchases() {
        seed(OrderStatus.CONFIRMED,1,"STANDARD_COURIER",false);
        seed(OrderStatus.CANCELLED,2,"STANDARD_COURIER",false);
        var refunded=seed(OrderStatus.COMPLETED,3,"STANDARD_COURIER",false);refunded.beginRefund(91L);refunded.completeRefund(false);orders.saveAndFlush(refunded);
        seed(OrderStatus.COMPLETED,4,"STANDARD_COURIER",true);
        var collected=seed(OrderStatus.COMPLETED,5,"STANDARD_COURIER",true);collected.collectDeferred("receipt");orders.saveAndFlush(collected);
        assertThat(orders.findPurchasedIds(17L,List.of(1L,2L,3L,4L,5L))).containsExactly(5L);
        assertThat(orders.findBestSellingProductIds(org.springframework.data.domain.PageRequest.of(0,10))).hasSize(1);
    }
    @Test void shipmentFactsAdvanceLifecycleWithoutChangingPaymentAndRejectOldConflictingOrForeignFacts() {
        var order=seed(OrderStatus.PAID,1,"STANDARD_COURIER",false);
        assertThat(shipments.apply(order.getId(),command(1,"IN_TRANSIT")).orderStatus()).isEqualTo("SHIPPED");
        var complete=shipments.apply(order.getId(),command(3,"DELIVERED"));
        assertThat(complete.orderStatus()).isEqualTo("COMPLETED");assertThat(complete.paymentState()).isEqualTo("PAID");
        assertThat(shipments.apply(order.getId(),command(1,"IN_TRANSIT")).revision()).isEqualTo(3);
        assertThat(shipments.apply(order.getId(),command(3,"DELIVERED")).outcome()).isEqualTo("ALREADY_APPLIED");
        assertThatThrownBy(() -> shipments.apply(order.getId(),command(3,"IN_TRANSIT"))).hasMessageContaining("409");
        assertThatThrownBy(() -> shipments.apply(order.getId(),new OrderShipmentService.Command("MT-FOREIGN-0001","STANDARD_COURIER",4L,"DELIVERED"))).hasMessageContaining("409");
    }
    @Test void deliveryDoesNotSettleDeferredPaymentOrReleaseAFinancialHold() {
        var deferred=seed(OrderStatus.CONFIRMED,1,"STANDARD_COURIER",true);
        var result=shipments.apply(deferred.getId(),command(1,"DELIVERED"));
        assertThat(result.orderStatus()).isEqualTo("COMPLETED");assertThat(result.paymentState()).isEqualTo("COLLECTION_DUE");
        var held=seed(OrderStatus.PAID,2,"STANDARD_COURIER",false);held.beginRefund(92L);orders.saveAndFlush(held);
        assertThatThrownBy(() -> shipments.apply(held.getId(),command(1,"IN_TRANSIT"))).hasMessageContaining("409");
        assertThat(orders.findById(held.getId()).orElseThrow().getPaymentState()).isEqualTo("REFUND_PENDING");
    }
    @Test void unconfirmedMoneyRetriesAndCarrierCancellationNeverCancelsAnOrderImplicitly() {
        var unpaid=seed(OrderStatus.CONFIRMED,1,"STANDARD_COURIER",false);
        assertThatThrownBy(() -> shipments.apply(unpaid.getId(),command(1,"DELIVERED"))).hasMessageContaining("425");
        var paid=seed(OrderStatus.PAID,2,"STANDARD_COURIER",false);
        assertThatThrownBy(() -> shipments.apply(paid.getId(),command(1,"CANCELLED"))).hasMessageContaining("409");
        assertThat(orders.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }
    @Test void paidStorePickupCanCompleteWithoutAnInventedShippingStage() {
        var order=seed(OrderStatus.PAID,1,"STORE_PICKUP",false);
        var result=shipments.apply(order.getId(),new OrderShipmentService.Command("MT-TEST-0001","STORE_PICKUP",1L,"PICKED_UP"));
        assertThat(result.orderStatus()).isEqualTo("COMPLETED");assertThat(result.paymentState()).isEqualTo("PAID");
    }
    @Test void inconsistentDeferredSagaCannotBeHiddenByACompletedDelivery() {
        var order=seed(OrderStatus.CONFIRMED,1,"STANDARD_COURIER",true);
        var saga=sagas.findByOrderId(order.getId()).orElseThrow();saga.completed();sagas.saveAndFlush(saga);
        assertThatThrownBy(() -> shipments.apply(order.getId(),command(1,"DELIVERED"))).hasMessageContaining("409");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
