package org.demo.orderservice.order;

import org.demo.orderservice.inventory.*;
import org.demo.orderservice.payment.PaymentCancellationClient;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a06;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=1","order.saga.recovery-enabled=false","order.saga.reconciliation-batch-size=2"})
class OrderLifecycleTest {
    @Autowired OrderRepository orders;
    @Autowired OrderSagaRepository sagas;
    @Autowired OrderSagaStateStore states;
    @Autowired OrderSagaService sagaService;
    @Autowired OrderReconciliationStore reconciliationStates;
    @Autowired OrderReconciliationService reconciliation;
    @Autowired DeferredFulfillmentService fulfillment;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean InventoryClient inventory;
    @MockitoBean PaymentCancellationClient payment;
    @BeforeEach void clear() { sagas.deleteAll();orders.deleteAll(); }
    private Long seed(OrderStatus status,boolean withSaga) {
        var order=new Order(BigDecimal.TEN,17L,"owner@example.test",new DeliveryAddressRequest("Jan","Test","Street","00-001","City","PL",null));
        order.addItem(1L,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN);order.changeStatus(status);
        if(status!=OrderStatus.CREATED) order.assignInventoryReservation(42L);
        order=orders.saveAndFlush(order);
        if(withSaga) {
            var saga=new OrderSaga(order.getId());
            if(status==OrderStatus.CONFIRMED) saga.waitingForPayment();
            else if(status==OrderStatus.PAID) saga.completed();
            sagas.saveAndFlush(saga);
        }
        return order.getId();
    }
    private InventoryReservationResponse reserved(Long id) {
        return new InventoryReservationResponse(42L,id,"RESERVED",Instant.now().plusSeconds(600),List.of(new InventoryReservationItemRequest(1L,1)));
    }
    @Test void boundedScanPassesHealthyPagesAndRecoversOrFlagsEveryOrphan() {
        seed(OrderStatus.CONFIRMED,true);seed(OrderStatus.CONFIRMED,true);
        var draft=seed(OrderStatus.CREATED,false);var confirmed=seed(OrderStatus.CONFIRMED,false);var paid=seed(OrderStatus.PAID,false);
        for(int n=0;n<8;n++) reconciliation.scanMissing();
        assertThat(sagas.count()).isEqualTo(5);
        assertThat(sagas.findByOrderId(draft).orElseThrow().getState()).isEqualTo(OrderSagaState.INVENTORY_RESERVATION_PENDING);
        for(Long id:List.of(confirmed,paid)) {
            assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(OrderSagaState.RECONCILIATION_REQUIRED);
            assertThat(orders.findById(id).orElseThrow().isLifecycleReviewRequired()).isTrue();
        }
        assertThat(orders.findById(paid).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(orders.findPurchasedIds(17L,List.of(1L))).isEmpty();
        assertThat(orders.findBestSellingProductIds(org.springframework.data.domain.PageRequest.of(0,10))).isEmpty();
        verifyNoInteractions(inventory,payment);
    }
    @Test void twoReplicasAttachOnlyOneMissingSaga() throws Exception {
        var id=seed(OrderStatus.CREATED,false);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> { start.await();reconciliationStates.attachMissing(id);return true; });
            var second=pool.submit(() -> { start.await();reconciliationStates.attachMissing(id);return true; });start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();assertThat(second.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(sagas.count()).isEqualTo(1);
    }
    @Test void verifiedUnpaidReservationResumesWithoutRepricingOrASecondRemoteOperation() {
        var id=seed(OrderStatus.CONFIRMED,false);
        when(inventory.lookupForReconciliation(id)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(orders.count()).isEqualTo(1);return reserved(id);
        });
        assertThat(reconciliation.reconcile(id).lifecycleReviewRequired()).isFalse();
        assertThat(reconciliation.reconcile(id).totalPrice()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(OrderSagaState.WAITING_FOR_PAYMENT);
        verify(inventory).lookupForReconciliation(id);verifyNoInteractions(payment);
    }
    @Test void invalidIdentityItemsStatusAndExpiryRemainFlagged() {
        var id=seed(OrderStatus.CONFIRMED,false);
        for(var invalid:List.of(
                new InventoryReservationResponse(42L,id+1,"RESERVED",Instant.now().plusSeconds(600),reserved(id).items()),
                new InventoryReservationResponse(42L,id,"CONFIRMED",Instant.now().plusSeconds(600),reserved(id).items()),
                new InventoryReservationResponse(42L,id,"RESERVED",Instant.EPOCH,reserved(id).items()),
                new InventoryReservationResponse(42L,id,"RESERVED",Instant.now().plusSeconds(600),List.of(new InventoryReservationItemRequest(1L,2))))) {
            when(inventory.lookupForReconciliation(id)).thenReturn(invalid);
            assertThatThrownBy(() -> reconciliation.reconcile(id)).hasMessageContaining("409");
            assertThat(orders.findById(id).orElseThrow().isLifecycleReviewRequired()).isTrue();
            assertThat(sagas.findByOrderId(id).orElseThrow().getProcessingToken()).isNull();
        }
    }
    @Test void paidAndCancelledHistoryCannotBeReconstructedFromInventoryAlone() {
        for(var status:List.of(OrderStatus.PAID,OrderStatus.CANCELLED)) {
            var id=seed(status,false);reconciliationStates.attachMissing(id);
            assertThatThrownBy(() -> reconciliation.reconcile(id)).hasMessageContaining("409");
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(status);
        }
        verifyNoInteractions(inventory,payment);
    }
    @Test void malformedHistoricalTotalsCannotResumeReservationOrPayment() {
        var id=seed(OrderStatus.CREATED,false);jdbc.update("update orders set total_price=99 where id=?",id);
        reconciliationStates.attachMissing(id);sagaService.resume(id);
        assertThat(orders.findById(id).orElseThrow().isLifecycleReviewRequired()).isTrue();
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(OrderSagaState.RECONCILIATION_REQUIRED);
        verifyNoInteractions(inventory,payment);
    }
    @Test void apiCannotConfirmADraftOrPayAnOrderWithoutSaga() {
        var draft=seed(OrderStatus.CREATED,true);var orphan=seed(OrderStatus.CONFIRMED,false);
        assertThat(orders.findById(orphan).orElseThrow().isLifecycleReviewRequired()).isFalse();
        assertThat(orderService.get(orphan).lifecycleReviewRequired()).isTrue();
        assertThatThrownBy(() -> fulfillment.transition(draft,OrderStatus.CONFIRMED)).hasMessageContaining("409");
        assertThatThrownBy(() -> fulfillment.transition(orphan,OrderStatus.PAID)).hasMessageContaining("409");
        assertThat(orders.findById(draft).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
    }
    @Test void staleAndExpiredReservationOwnersCannotCommit() {
        var id=seed(OrderStatus.CREATED,true);var old=states.claim(id,false);
        jdbc.update("update order_sagas set processing_until=? where order_id=?",java.sql.Timestamp.from(Instant.EPOCH),id);
        assertThat(states.complete(old,42L)).isFalse();
        var replacement=states.claim(id,false);assertThat(states.complete(old,42L)).isFalse();states.complete(replacement,42L);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
    @Test void lateReservationResultCannotRegressALegacyPaidOrder() {
        var id=seed(OrderStatus.CREATED,true);var ticket=states.claim(id,false);
        var advanced=orders.findById(id).orElseThrow();advanced.changeStatus(OrderStatus.PAID);orders.saveAndFlush(advanced);
        states.complete(ticket,42L);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(OrderSagaState.RECONCILIATION_REQUIRED);
    }
    @Test void optimisticVersionRejectsAWriterWithAnObsoleteOrderSnapshot() {
        var id=seed(OrderStatus.CONFIRMED,true);var old=orders.findById(id).orElseThrow();fulfillment.transition(id,OrderStatus.PAID);
        old.changeStatus(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> orders.saveAndFlush(old)).isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }
    @Test void concurrentPaidAndCancelledHaveExactlyOneWinner() throws Exception {
        var id=seed(OrderStatus.CONFIRMED,true);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var paid=pool.submit(() -> { start.await();try { fulfillment.transition(id,OrderStatus.PAID);return 200; } catch(org.springframework.web.server.ResponseStatusException expected) {return 409;} });
            var cancelled=pool.submit(() -> { start.await();try { sagaService.compensate(id);return 200; } catch(org.springframework.web.server.ResponseStatusException expected) {return 409;} });
            start.countDown();assertThat(List.of(paid.get(10,TimeUnit.SECONDS),cancelled.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void reservationResponseForAnotherOrderNeverConfirmsThisOne() {
        var id=seed(OrderStatus.CREATED,true);
        when(inventory.reserve(eq(id),anyList())).thenReturn(new InventoryReservationResponse(42L,id+1,"RESERVED",null));
        assertThatThrownBy(() -> sagaService.resume(id)).isInstanceOf(InventoryServiceException.class);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(orders.findById(id).orElseThrow().getInventoryReservationId()).isNull();
    }
    @Test void concurrentChangeDuringReservationAssessmentInvalidatesItsSnapshot() {
        var id=seed(OrderStatus.CONFIRMED,false);
        when(inventory.lookupForReconciliation(id)).thenAnswer(call -> {
            var changed=orders.findById(id).orElseThrow();changed.changeStatus(OrderStatus.PAID);orders.saveAndFlush(changed);return reserved(id);
        });
        assertThatThrownBy(() -> reconciliation.reconcile(id)).hasMessageContaining("409");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(orders.findById(id).orElseThrow().isLifecycleReviewRequired()).isTrue();
    }
}
