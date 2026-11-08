package org.demo.paymentservice.payment;

import org.demo.paymentservice.billing.*;
import org.demo.paymentservice.delivery.DeliveryClient;
import org.demo.paymentservice.followup.*;
import org.demo.paymentservice.inventory.InventoryClient;
import org.demo.paymentservice.order.*;
import org.demo.paymentservice.outbox.*;
import org.demo.paymentservice.promotion.PromotionClient;
import org.demo.paymentservice.web.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a10;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.hikari.maximum-pool-size=3"})
class DeferredPaymentLifecycleTest {
    @Autowired PaymentService service;
    @Autowired PaymentCollectionService collection;
    @Autowired PaymentRepository payments;
    @Autowired PaymentFollowUpRepository tasks;
    @Autowired PaymentOutboxRepository events;
    @Autowired FollowUpRunner runner;
    @Autowired PaymentStateStore states;
    @MockitoBean OrderClient orders;
    @MockitoBean InventoryClient inventory;
    @MockitoBean DeliveryClient delivery;
    @MockitoBean PromotionClient promotion;
    @MockitoBean BillingClient billing;
    @MockitoBean PaymentGateway gateway;
    private final Map<Long,OrderResponse> snapshots=new ConcurrentHashMap<>();
    private static final BillingDataRequest BUYER=new BillingDataRequest(false,"Buyer",null,null,"Street","00-001","City","PL");
    @BeforeEach void setup() {
        when(gateway.supportedMethods()).thenReturn(java.util.EnumSet.allOf(PaymentMethod.class));
        tasks.deleteAll();events.deleteAll();payments.deleteAll();snapshots.clear();
        when(orders.getOrder(anyLong())).thenAnswer(call -> snapshots.get(call.getArgument(0)));
        when(orders.acceptDeferred(anyLong(),anyLong(),anyString(),any(),anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Long id=call.getArgument(0);var accepted=order(id,snapshots.get(id).deliveryMethod(),"CONFIRMED","COLLECTION_DUE",call.getArgument(2),call.getArgument(1));
            snapshots.put(id,accepted);return accepted;
        });
        when(orders.collectDeferred(anyLong(),anyLong(),anyString(),any(),anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Long id=call.getArgument(0);var prior=snapshots.get(id);var paid=order(id,prior.deliveryMethod(),"COMPLETED","PAID",prior.deferredPaymentMethod(),call.getArgument(1));
            snapshots.put(id,paid);return paid;
        });
        when(billing.issue(any(BillingDataRequest.class),any())).thenReturn(901L);
        doThrow(new IllegalStateException("No online provider in this test")).when(gateway).requireAvailable();
    }
    private OrderResponse order(Long id,String method,String status,String paymentState,String deferred,Long paymentId) {
        return new OrderResponse(id,List.of(new OrderItemResponse(1L,1L,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN)),BigDecimal.TEN,status,1000+id,null,method,
                BigDecimal.ZERO,"COUPON",BigDecimal.ZERO,"STORE_PICKUP".equals(method)?"STORE_PICKUP":"HOME_DELIVERY",null,null,null,true,17L,"owner@example.test",paymentState,deferred,paymentId);
    }
    private PaymentResponse accept(long id,boolean pickup) {
        snapshots.put(id,order(id,pickup?"STORE_PICKUP":"STANDARD_COURIER","CONFIRMED","UNPAID",null,null));
        return service.create(new CreatePaymentRequest(id,pickup?"CASH_AT_PICKUP":"CASH_ON_DELIVERY",false,null,null,17L,true,BUYER));
    }
    private CollectPaymentRequest receipt(String reference) { return new CollectPaymentRequest(BigDecimal.TEN,"PLN",reference); }
    private void handedOver(long id,boolean pickup) {
        when(delivery.tracking(id)).thenReturn(new DeliveryClient.Tracking(id,pickup?"STORE_PICKUP":"STANDARD_COURIER",pickup?"PICKED_UP":"DELIVERED"));
    }
    @Test void deferredAcceptanceProtectsInventoryAndStartsDeliveryWithoutPayingOrCallingAGateway() {
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(payments.count()).isEqualTo(1);return null; })
                .when(inventory).confirmDeferred(101L,1101L);
        var pending=accept(101,false);
        assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);
        verify(inventory).confirmDeferred(101L,1101L);verify(delivery).ensureTracking(101L,"STANDARD_COURIER");verify(promotion).redeemCoupon("COUPON",101L);
        verifyNoInteractions(gateway);verify(billing,never()).issue(any(BillingDataRequest.class),any());
        assertThat(tasks.findByOrderIdAndKind(101L,FollowUpKind.INVOICE_ISSUE).orElseThrow().getStatus()).isEqualTo(FollowUpStatus.WAITING);
        assertThat(events.findAll()).singleElement().satisfies(event -> { assertThat(event.getEventType()).isEqualTo("PAYMENT_PENDING");assertThat(event.getInvoiceId()).isNull(); });
    }
    @Test void matchingCashReceiptCompletesSettlementThenInvoiceAndConfirmationExactlyOnce() {
        var pending=accept(102,true);handedOver(102,true);
        var collected=collection.collect(pending.id(),receipt("RECEIPT-102"));runner.runOrder(102L);
        assertThat(collected.status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(collected.collectionReference()).isEqualTo("RECEIPT-102");assertThat(collected.collectedAt()).isNotNull();
        assertThat(collection.collect(pending.id(),receipt("RECEIPT-102"))).isEqualTo(collected);
        assertThat(tasks.findAll()).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(FollowUpStatus.SUCCEEDED));
        verify(orders,times(1)).collectDeferred(102L,pending.id(),"RECEIPT-102",new BigDecimal("10.00"),"PLN");
        verify(billing,times(1)).issue(eq(BUYER),any());verify(inventory,times(1)).confirmDeferred(102L,1102L);
        assertThat(events.findAll().stream().filter(event -> "PAYMENT_SUCCEEDED".equals(event.getEventType())).toList())
                .singleElement().satisfies(event -> assertThat(event.getInvoiceId()).isEqualTo(901L));
    }
    @Test void collectionRequiresHandoverAndExactAmountAndCannotChangeAnExistingReceipt() {
        var pending=accept(103,false);
        when(delivery.tracking(103L)).thenReturn(new DeliveryClient.Tracking(103L,"STANDARD_COURIER","LABEL_CREATED"));
        assertThatThrownBy(() -> collection.collect(pending.id(),receipt("RECEIPT-103"))).hasMessageContaining("409");
        assertThatThrownBy(() -> collection.collect(pending.id(),new CollectPaymentRequest(BigDecimal.ONE,"PLN","RECEIPT-103"))).hasMessageContaining("409");
        assertThat(payments.findById(pending.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        handedOver(103,false);collection.collect(pending.id(),receipt("RECEIPT-103"));
        assertThatThrownBy(() -> collection.collect(pending.id(),receipt("OTHER-103"))).isInstanceOf(PaymentIdempotencyConflictException.class);
    }
    @Test void concurrentCollectionOnTwoThreadsReplaysTheSameRecordedResult() throws Exception {
        var pending=accept(104,false);handedOver(104,false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> collection.collect(pending.id(),receipt("RECEIPT-104")));
            var second=pool.submit(() -> collection.collect(pending.id(),receipt("RECEIPT-104")));
            assertThat(first.get(8,TimeUnit.SECONDS)).isEqualTo(second.get(8,TimeUnit.SECONDS));
        }
        runner.runOrder(104L);
        assertThat(payments.findById(pending.id()).orElseThrow().getCollectionReference()).isEqualTo("RECEIPT-104");
        assertThat(events.findAll().stream().filter(event -> "PAYMENT_SUCCEEDED".equals(event.getEventType())).count()).isEqualTo(1);
    }
    @Test void aReceiptCannotBeReusedForAnotherPaymentAndTheLosingSettlementRemainsWaiting() {
        var one=accept(105,false);var two=accept(106,true);handedOver(105,false);handedOver(106,true);
        collection.collect(one.id(),receipt("SHARED-RECEIPT"));
        assertThatThrownBy(() -> collection.collect(two.id(),receipt("SHARED-RECEIPT"))).hasMessageContaining("409");
        assertThat(payments.findById(two.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(tasks.findByOrderIdAndKind(106L,FollowUpKind.ORDER_SETTLEMENT).orElseThrow().getStatus()).isEqualTo(FollowUpStatus.WAITING);
    }
    @Test void methodMismatchAndFailedStockConfirmationNeverReportAcceptedFulfillment() {
        snapshots.put(107L,order(107L,"STORE_PICKUP","CONFIRMED","UNPAID",null,null));
        assertThatThrownBy(() -> service.create(new CreatePaymentRequest(107L,"CASH_ON_DELIVERY",false))).hasMessageContaining("409");
        doThrow(new org.demo.paymentservice.inventory.InventoryServiceException("down")).when(inventory).confirmDeferred(108L,1108L);
        assertThatThrownBy(() -> accept(108,false)).isInstanceOf(org.demo.paymentservice.inventory.InventoryServiceException.class);
        assertThat(payments.findByOrderId(108L).orElseThrow().resultRecorded()).isFalse();
        assertThat(tasks.count()).isZero();verifyNoInteractions(delivery);
    }
    @Test void aFailedPendingPlanWriteDoesNotCommitTheAcceptedResult() {
        var ticket=states.claim(new Payment(109L,BigDecimal.TEN,"CASH_ON_DELIVERY","key-109"));
        var a=new PaymentFollowUp(109L,FollowUpKind.ORDER_SETTLEMENT,"{}",true);
        var b=new PaymentFollowUp(109L,FollowUpKind.ORDER_SETTLEMENT,"{}",true);
        assertThatThrownBy(() -> states.finish(ticket,null,List.of(a,b))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        states.release(ticket);
        assertThat(payments.findByOrderId(109L).orElseThrow().resultRecorded()).isFalse();assertThat(tasks.count()).isZero();
    }
}
