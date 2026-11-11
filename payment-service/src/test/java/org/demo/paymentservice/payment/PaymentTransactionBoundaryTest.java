package org.demo.paymentservice.payment;

import org.demo.paymentservice.order.*;
import org.demo.paymentservice.inventory.InventoryClient;
import org.demo.paymentservice.billing.BillingClient;
import org.demo.paymentservice.delivery.DeliveryClient;
import org.demo.paymentservice.promotion.PromotionClient;
import org.demo.paymentservice.outbox.PaymentOutboxRepository;
import org.demo.paymentservice.web.CreatePaymentRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a31payment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.hikari.maximum-pool-size=1","payment.concurrent-wait-ms=0"})
class PaymentTransactionBoundaryTest {
    @Autowired PaymentService service;
    @Autowired PaymentRepository payments;
    @Autowired PaymentOutboxRepository events;
    @Autowired PaymentStateStore states;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.demo.paymentservice.followup.PaymentFollowUpRepository followUps;
    @MockitoBean OrderClient orders;
    @MockitoBean InventoryClient inventory;
    @MockitoBean BillingClient billing;
    @MockitoBean DeliveryClient delivery;
    @MockitoBean PromotionClient promotions;
    @MockitoBean PaymentGateway gateway;
    @BeforeEach void clear() {
        when(gateway.supportedMethods()).thenReturn(java.util.EnumSet.allOf(PaymentMethod.class));
        followUps.deleteAll();events.deleteAll(); payments.deleteAll();
    }
    @Test void providerWaitHoldsNoTransactionAndOtherRequestsCanUseTheSingleConnection() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(orders.getOrder(71L)).thenReturn(new OrderResponse(71L,BigDecimal.TEN,"CONFIRMED",null,null),new OrderResponse(71L,BigDecimal.TEN,"PAID",null,null));
        when(orders.markPaid(71L)).thenReturn(new OrderResponse(71L,BigDecimal.TEN,"PAID",null,null));
        when(gateway.authorize(anyLong(),any(),anyString(),any(),anyString(),anyBoolean())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
            return new PaymentGateway.Authorization(true,"TEST-AUTH",null);
        });
        try(var worker=Executors.newSingleThreadExecutor()) {
            var request=new CreatePaymentRequest(71L,"CARD",false);
            var result=worker.submit(() -> service.create(request));
            try {
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                assertThat(payments.count()).isEqualTo(1);
                assertThat(payments.findByOrderId(71L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
                assertThatThrownBy(() -> service.create(request)).hasMessageContaining("409");
            } finally { release.countDown(); }
            assertThat(result.get(5,TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payments.findByOrderId(71L).orElseThrow().getProcessingToken()).isNull();
            assertThat(service.create(request).status()).isEqualTo(PaymentStatus.SUCCEEDED);
            verify(gateway,times(1)).authorize(anyLong(),any(),anyString(),any(),anyString(),anyBoolean());
        }
    }
    @Test void expiredLeaseCanBeClaimedAndStaleOwnerCannotCommit() {
        var first=states.claim(new Payment(72L,BigDecimal.TEN,"CARD","stable-key"));
        assertThatThrownBy(() -> states.claim(new Payment(72L,BigDecimal.TEN,"CARD","new-key"))).hasMessageContaining("409");
        jdbc.update("update payments set processing_until=? where order_id=72",java.sql.Timestamp.from(java.time.Instant.EPOCH));
        var second=states.claim(new Payment(72L,BigDecimal.TEN,"CARD","new-key"));
        assertThat(second.payment().getIdempotencyKey()).isEqualTo("stable-key");
        first.payment().markSucceeded("STALE");
        assertThatThrownBy(() -> states.finish(first,null)).hasMessageContaining("409");
        states.release(first);
        second.payment().markSucceeded("CURRENT"); states.finish(second,null);
        assertThat(payments.findByOrderId(72L).orElseThrow().getProviderReference()).isEqualTo("CURRENT");
    }

    @Test void failedOutboxWriteRollsBackSuccessAndCleanupCannotCommitDetachedSuccess() {
        var ticket=states.claim(new Payment(73L,BigDecimal.TEN,"CARD","rollback-key"));
        ticket.payment().markSucceeded("AUTHORIZED");
        var invalidEvent=new org.demo.paymentservice.outbox.PaymentOutboxEvent(
                "PAYMENT_SUCCEEDED",73L,BigDecimal.TEN,"x".repeat(500));
        assertThatThrownBy(() -> states.finish(ticket,invalidEvent)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        states.release(ticket);
        var persisted=payments.findByOrderId(73L).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(persisted.getProcessingToken()).isNull();
        assertThat(persisted.getProviderReference()).isNull();
        assertThat(events.count()).isZero();
    }

    @Test void recoveryKeepsAuthorizationAndRejectsChangedAmountOrMethod() {
        var first=states.claim(new Payment(81L,BigDecimal.TEN,"CARD","original-key"));
        states.recordAuthorization(first,"AUTH-81");
        states.release(first);
        assertThatThrownBy(() -> states.claim(new Payment(81L,BigDecimal.ONE,"CARD","other-key")))
                .isInstanceOf(PaymentIdempotencyConflictException.class);
        assertThatThrownBy(() -> states.claim(new Payment(81L,BigDecimal.TEN,"BLIK","other-key")))
                .isInstanceOf(PaymentIdempotencyConflictException.class);
        var retry=states.claim(new Payment(81L,BigDecimal.TEN,"CARD","other-key"));
        assertThat(retry.payment().getProviderReference()).isEqualTo("AUTH-81");
        assertThat(retry.payment().getIdempotencyKey()).isEqualTo("original-key");
        states.release(retry);
    }

    @Test void completedPendingAndDeclinedResultsCannotBeClaimedAgain() {
        for (long orderId : new long[]{82,83}) {
            var ticket=states.claim(new Payment(orderId,BigDecimal.TEN,"CARD","key-"+orderId));
            if (orderId==83) ticket.payment().markFailed("Declined");
            states.finish(ticket,null);
            var repeat=states.claim(new Payment(orderId,BigDecimal.TEN,"CARD","key-"+orderId));
            assertThat(repeat.token()).isNull();
            assertThat(repeat.payment().getStatus()).isEqualTo(orderId==82 ? PaymentStatus.PENDING : PaymentStatus.FAILED);
        }
    }

    @Test void commandFingerprintIsCheckedEvenWhenTheResultIsAlreadyCompleted() {
        var payment=new Payment(84L,BigDecimal.TEN,"CARD","key-84");payment.bindRequest("first");
        var ticket=states.claim(payment);ticket.payment().markSucceeded("AUTH");states.finish(ticket,null);
        var changed=new Payment(84L,BigDecimal.TEN,"CARD","key-84");changed.bindRequest("changed");
        assertThatThrownBy(() -> states.claim(changed)).isInstanceOf(PaymentIdempotencyConflictException.class);
    }

    @Test void expiredOwnerCannotAuthorizeOrPerformFurtherEffectsEvenBeforeReclaim() {
        var ticket=states.claim(new Payment(85L,BigDecimal.TEN,"CARD","key-85"));
        jdbc.update("update payments set processing_until=? where order_id=85",java.sql.Timestamp.from(java.time.Instant.EPOCH));
        assertThatThrownBy(() -> states.assertOwner(ticket)).hasMessageContaining("409");
        assertThatThrownBy(() -> states.recordAuthorization(ticket,"STALE")).hasMessageContaining("409");
        assertThat(payments.findByOrderId(85L).orElseThrow().getProviderReference()).isNull();
    }

    @Test void simultaneousFollowerReturnsTheExactCommittedResultWithoutRepeatingEffects() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(service,"concurrentWaitMs",5000L);
        var entered=new CountDownLatch(1); var unblock=new CountDownLatch(1);
        when(orders.getOrder(86L)).thenReturn(new OrderResponse(86L,BigDecimal.TEN,"CONFIRMED",null,null),new OrderResponse(86L,BigDecimal.TEN,"PAID",null,null));
        when(orders.markPaid(86L)).thenReturn(new OrderResponse(86L,BigDecimal.TEN,"PAID",null,null));
        when(gateway.authorize(anyLong(),any(),anyString(),any(),anyString(),anyBoolean())).thenAnswer(call -> {
            entered.countDown();if(!unblock.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
            return new PaymentGateway.Authorization(true,"AUTH-86",null);
        });
        try (var workers=Executors.newFixedThreadPool(2)) {
            var request=new CreatePaymentRequest(86L,"CARD",false);
            var first=workers.submit(() -> service.create(request));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                var second=workers.submit(() -> service.create(request));
                Thread.sleep(100);assertThat(second.isDone()).isFalse();unblock.countDown();
                assertThat(second.get(4,TimeUnit.SECONDS)).isEqualTo(first.get(4,TimeUnit.SECONDS));
                verify(gateway,times(1)).authorize(anyLong(),any(),anyString(),any(),anyString(),anyBoolean());
                verify(orders,times(1)).markPaid(86L);verify(delivery,times(1)).ensureTracking(86L,null);
            } finally { unblock.countDown(); }
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(service,"concurrentWaitMs",0L); }
    }
}
