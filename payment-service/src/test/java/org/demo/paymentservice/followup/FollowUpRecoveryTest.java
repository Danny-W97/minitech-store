package org.demo.paymentservice.followup;

import org.demo.paymentservice.billing.*;
import org.demo.paymentservice.delivery.*;
import org.demo.paymentservice.promotion.*;
import org.demo.paymentservice.payment.*;
import org.demo.paymentservice.order.*;
import org.demo.paymentservice.outbox.*;
import org.demo.paymentservice.web.CreatePaymentRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a09;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=1","payment.follow-ups.max-attempts=2"})
class FollowUpRecoveryTest {
    @Autowired PaymentStateStore payments;
    @Autowired PaymentRepository paymentRows;
    @Autowired PaymentFollowUpRepository tasks;
    @Autowired PaymentOutboxRepository events;
    @Autowired FollowUpPlanner planner;
    @Autowired FollowUpRunner runner;
    @Autowired FollowUpStateStore states;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean BillingClient billing;
    @MockitoBean DeliveryClient delivery;
    @MockitoBean PromotionClient promotions;
    @MockitoBean OrderClient orders;
    private static final BillingDataRequest BUYER=new BillingDataRequest(false,"Buyer",null,null,"Private Street","00-001","City","PL");
    @BeforeEach void clear() {
        tasks.deleteAll();events.deleteAll();paymentRows.deleteAll();
        when(orders.getOrder(anyLong())).thenAnswer(call -> order(call.getArgument(0),"owner@example.test"));
    }

    private OrderResponse order(long id,String email) {
        return new OrderResponse(id,List.of(new OrderItemResponse(1L,1L,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN)),
                BigDecimal.TEN,"PAID",9L,null,"STANDARD_COURIER",BigDecimal.ZERO,"TEST",BigDecimal.ZERO,
                "HOME_DELIVERY",LocalDate.of(2026,10,30),null,null,true,1L,email);
    }
    private List<PaymentFollowUp> plan(long id,boolean invoice,boolean notify) {
        var request=new CreatePaymentRequest(id,"CARD",false,null,null,null,invoice,invoice ? BUYER : null);
        return planner.plan(request,order(id,notify ? "owner@example.test" : null),
                notify ? new PaymentOutboxEvent("PAYMENT_SUCCEEDED",id,BigDecimal.TEN,"owner@example.test") : null);
    }
    private void seed(long id,boolean invoice,boolean notify) {
        var ticket=payments.claim(new Payment(id,BigDecimal.TEN,"CARD","key-"+id));
        ticket.payment().markSucceeded("AUTH");payments.finish(ticket,null,plan(id,invoice,notify));
    }
    private PaymentFollowUp task(long id,FollowUpKind kind) { return tasks.findByOrderIdAndKind(id,kind).orElseThrow(); }
    private void due() { jdbc.update("update payment_follow_ups set next_attempt_at=? where status='RETRY'",java.sql.Timestamp.from(Instant.EPOCH)); }

    @Test void unavailableDependenciesRemainDurableAndRecoverWithoutAnotherPaymentRequest() {
        seed(101,true,true);
        doThrow(new PromotionServiceException("down",null)).when(promotions).redeemCoupon(anyString(),anyLong());
        doThrow(new DeliveryServiceException("down",null)).when(delivery).ensureTracking(anyLong(),anyString());
        when(billing.issue(any(BillingDataRequest.class),any())).thenThrow(new BillingServiceException("down",null));
        runner.runOrder(101L);
        assertThat(paymentRows.findByOrderId(101L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(tasks.findAll().stream().filter(t -> t.getStatus()==FollowUpStatus.RETRY).count()).isEqualTo(3);
        assertThat(task(101,FollowUpKind.PAYMENT_NOTIFICATION).getStatus()).isEqualTo(FollowUpStatus.WAITING);
        assertThat(events.count()).isZero();
        reset(promotions,delivery,billing);due();
        when(billing.issue(any(BillingDataRequest.class),any())).thenReturn(901L);
        runner.runOrder(101L);
        assertThat(tasks.findAll()).allSatisfy(t -> { assertThat(t.getStatus()).isEqualTo(FollowUpStatus.SUCCEEDED);assertThat(t.getPayload()).isNull(); });
        assertThat(events.findAll()).singleElement().satisfies(event -> assertThat(event.getInvoiceId()).isEqualTo(901L));
        verify(promotions).redeemCoupon("TEST",101L);verify(delivery).ensureTracking(101L,"STANDARD_COURIER");
        verify(billing).issue(eq(BUYER),argThat(snapshot -> snapshot.deliveryDate().equals(LocalDate.of(2026,10,30))));
        runner.runOrder(101L);verifyNoMoreInteractions(promotions,delivery,billing);
    }

    @Test void businessEffectsDoNotRequireAnEmailOrNotificationEvent() {
        seed(102,false,false);runner.runOrder(102L);
        assertThat(tasks.count()).isEqualTo(2);assertThat(events.count()).isZero();
        verify(promotions).redeemCoupon("TEST",102L);verify(delivery).ensureTracking(102L,"STANDARD_COURIER");
        verifyNoInteractions(billing);
    }
    @Test void unreconciledHistoricalOrdersCannotStartNewFinancialOrFulfillmentEffects() {
        seed(191,true,true);var original=order(191,"owner@example.test");
        when(orders.getOrder(191L)).thenReturn(new OrderResponse(original.id(),original.items(),original.totalPrice(),original.status(),
                original.inventoryReservationId(),original.deliveryAddress(),original.deliveryMethod(),original.deliveryCost(),
                original.promotionCode(),original.discountAmount(),original.fulfillmentType(),original.deliveryDate(),original.deliverySlot(),
                original.pickupLocation(),original.invoiceRequested(),original.customerId(),original.customerEmail(),"PAID",null,null,true));
        runner.runOrder(191L);
        verifyNoInteractions(billing,delivery,promotions);
        assertThat(events.count()).isZero();
        assertThat(tasks.findAll().stream().filter(task -> task.getStatus()==FollowUpStatus.DEAD_LETTER).count()).isEqualTo(3);
    }

    @Test void networkCallsRunAfterPaymentCommitWithoutHoldingTheSingleConnection() {
        seed(103,true,true);
        when(billing.issue(any(BillingDataRequest.class),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(paymentRows.findByOrderId(103L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(tasks.count()).isEqualTo(4);return 903L;
        });
        runner.runOrder(103L);assertThat(task(103,FollowUpKind.INVOICE_ISSUE).getResultId()).isEqualTo(903L);
    }

    @Test void aDuplicateTaskRollsBackThePaymentResultAndAllItsTasks() {
        var ticket=payments.claim(new Payment(104L,BigDecimal.TEN,"CARD","key-104"));ticket.payment().markSucceeded("AUTH");
        var plan=plan(104,false,false);plan.add(new PaymentFollowUp(104L,FollowUpKind.SHIPMENT_TRACKING,"{}",false));
        assertThatThrownBy(() -> payments.finish(ticket,null,plan)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        payments.release(ticket);
        assertThat(paymentRows.findByOrderId(104L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(tasks.count()).isZero();assertThat(events.count()).isZero();
    }

    @Test void aStaleOwnerCannotCompleteOrFailTheSuccessorTask() {
        seed(105,true,true);Long id=task(105,FollowUpKind.INVOICE_ISSUE).getId();
        var first=states.claim(id);assertThat(states.claim(id)).isNull();
        jdbc.update("update payment_follow_ups set processing_until=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),id);
        assertThat(states.owns(first)).isFalse();var second=states.claim(id);
        states.complete(first,1L);states.fail(first,"STALE",true);
        assertThat(task(105,FollowUpKind.PAYMENT_NOTIFICATION).getStatus()).isEqualTo(FollowUpStatus.WAITING);
        states.complete(second,905L);
        states.complete(first,999L);states.fail(first,"STALE",true);
        assertThat(task(105,FollowUpKind.INVOICE_ISSUE).getResultId()).isEqualTo(905L);
        assertThat(task(105,FollowUpKind.PAYMENT_NOTIFICATION).getStatus()).isEqualTo(FollowUpStatus.PENDING);
    }

    @Test void exhaustedRetryCanBeReplayedButSuccessCannot() {
        seed(106,false,false);Long id=task(106,FollowUpKind.SHIPMENT_TRACKING).getId();
        doThrow(new DeliveryServiceException("down",null)).when(delivery).ensureTracking(anyLong(),anyString());
        runner.runOne(id);due();runner.runOne(id);
        assertThat(task(106,FollowUpKind.SHIPMENT_TRACKING).getStatus()).isEqualTo(FollowUpStatus.DEAD_LETTER);
        assertThat(task(106,FollowUpKind.SHIPMENT_TRACKING).getPayload()).isNotNull();
        assertThat(states.replay(id).replayCount()).isEqualTo(1);reset(delivery);runner.runOne(id);
        assertThat(task(106,FollowUpKind.SHIPMENT_TRACKING).getStatus()).isEqualTo(FollowUpStatus.SUCCEEDED);
        assertThatThrownBy(() -> states.replay(id)).hasMessageContaining("409");
    }

    @Test void malformedPayloadGoesToDeadLetterWithoutAnExternalCall() {
        seed(107,false,false);Long id=task(107,FollowUpKind.SHIPMENT_TRACKING).getId();
        jdbc.update("update payment_follow_ups set payload='not-json' where id=?",id);runner.runOne(id);
        assertThat(task(107,FollowUpKind.SHIPMENT_TRACKING).getStatus()).isEqualTo(FollowUpStatus.DEAD_LETTER);
        assertThat(task(107,FollowUpKind.SHIPMENT_TRACKING).getLastErrorCode()).isEqualTo("INVALID_PAYLOAD");
        verifyNoInteractions(delivery);
    }

    @Test void invalidBillingResponseDoesNotReleaseTheInvoiceNotification() {
        seed(108,true,true);runner.runOrder(108L);
        assertThat(task(108,FollowUpKind.INVOICE_ISSUE).getStatus()).isEqualTo(FollowUpStatus.RETRY);
        assertThat(task(108,FollowUpKind.PAYMENT_NOTIFICATION).getStatus()).isEqualTo(FollowUpStatus.WAITING);
        assertThat(events.count()).isZero();
    }

    @Test void permanentClientErrorsAreQuarantinedWithoutStoringTheResponseBody() {
        seed(109,false,false);Long id=task(109,FollowUpKind.SHIPMENT_TRACKING).getId();
        var response=org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_REQUEST,"bad",org.springframework.http.HttpHeaders.EMPTY,"private-body".getBytes(),null);
        doThrow(new DeliveryServiceException("private",response)).when(delivery).ensureTracking(anyLong(),anyString());runner.runOne(id);
        assertThat(task(109,FollowUpKind.SHIPMENT_TRACKING).getStatus()).isEqualTo(FollowUpStatus.DEAD_LETTER);
        assertThat(task(109,FollowUpKind.SHIPMENT_TRACKING).getLastErrorCode()).isEqualTo("HTTP_400");
    }

    @Test void notificationOutboxAndTaskCompletionAreAtomic() {
        seed(110,false,true);var notification=task(110,FollowUpKind.PAYMENT_NOTIFICATION);
        jdbc.update("update payment_follow_ups set payload=replace(payload,'owner@example.test',?) where id=?","x".repeat(500),notification.getId());
        var ticket=states.claim(notification.getId());
        assertThatThrownBy(() -> states.complete(ticket,null)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(task(110,FollowUpKind.PAYMENT_NOTIFICATION).getStatus()).isEqualTo(FollowUpStatus.PENDING);
        assertThat(task(110,FollowUpKind.PAYMENT_NOTIFICATION).getPayload()).isNotNull();assertThat(events.count()).isZero();
    }

    @Test void operatorPagesSelectOnlyMetadataAndEnforceTheirCursor() {
        seed(111,true,true);
        var page=states.list(null,111L,new org.demo.platformsecurity.ListWindow(0,2));
        assertThat(page.items()).hasSize(2);assertThat(page.hasMore()).isTrue();
        var next=states.list(null,111L,new org.demo.platformsecurity.ListWindow(Long.parseLong(page.nextCursor()),2));
        assertThat(next.items()).hasSize(2);assertThat(next.hasMore()).isFalse();
        assertThat(page.items()).extracting(FollowUpResponse::id).doesNotContainAnyElementsOf(next.items().stream().map(FollowUpResponse::id).toList());
        String payload=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(page.items());
        assertThat(payload).doesNotContain("Private Street","owner@example.test","billingData","payload");
        assertThatThrownBy(() -> states.list(null,0L,org.demo.platformsecurity.ListWindow.FIRST)).hasMessageContaining("400");
    }

    @Test void cancelledOrdersDoNotStartNewShipmentOrCouponEffects() {
        seed(112,false,false);
        when(orders.getOrder(112L)).thenReturn(new OrderResponse(112L,BigDecimal.TEN,"CANCELLED"));
        runner.runOrder(112L);
        assertThat(tasks.findAll()).allSatisfy(task -> {
            assertThat(task.getStatus()).isEqualTo(FollowUpStatus.DEAD_LETTER);
            assertThat(task.getLastErrorCode()).isEqualTo("ORDER_STATE_CONFLICT");
        });
        verifyNoInteractions(delivery,promotions);
    }
}
