package org.demo.orderservice.order;

import org.demo.orderservice.inventory.*;
import org.demo.orderservice.payment.*;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a11orders;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.hikari.maximum-pool-size=1","order.saga.recovery-enabled=false"})
class CancellationSafetyTest {
    @Autowired OrderRepository orders;
    @Autowired OrderSagaRepository sagas;
    @Autowired OrderSagaService service;
    @Autowired OrderSagaStateStore states;
    @Autowired DeferredFulfillmentService fulfillment;
    @MockitoBean InventoryClient inventory;
    @MockitoBean PaymentCancellationClient payment;
    @BeforeEach void clear() { sagas.deleteAll();orders.deleteAll(); }
    private Long seed(OrderStatus status,boolean withSaga) {
        var order=new Order(BigDecimal.TEN,null,"test@example.test",new DeliveryAddressRequest("Jan","Test","Street","00-001","City","PL",null));
        order.addItem(1L,"SKU","Test",1,BigDecimal.TEN,BigDecimal.TEN);order.assignInventoryReservation(42L);order.changeStatus(status);
        order=orders.saveAndFlush(order);
        if(withSaga) { var saga=new OrderSaga(order.getId());if(status==OrderStatus.PAID) saga.completed();else saga.waitingForPayment();sagas.saveAndFlush(saga); }
        return order.getId();
    }
    @Test void paidOrdersCannotBeCancelledWithOrWithoutASaga() {
        for(boolean withSaga:new boolean[]{true,false}) {
            var id=seed(OrderStatus.PAID,withSaga);
            assertThatThrownBy(() -> service.compensate(id)).hasMessageContaining("409");
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        }
        verifyNoInteractions(inventory,payment);
    }
    @Test void unpaidCancellationFencesBeforeReleaseAndReplaysWithoutRemoteCalls() {
        var id=seed(OrderStatus.CONFIRMED,true);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThatThrownBy(() -> fulfillment.transition(id,OrderStatus.PAID)).hasMessageContaining("409");return null; }).when(payment).fence(id);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null; }).when(inventory).releaseForCancellation(id,42L);
        service.compensate(id);service.compensate(id);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        var sequence=inOrder(payment,inventory);sequence.verify(payment).fence(id);sequence.verify(inventory).releaseForCancellation(id,42L);sequence.verifyNoMoreInteractions();
    }
    @Test void paymentRefusalRestoresSagaAndDoesNotReleaseInventory() {
        var id=seed(OrderStatus.CONFIRMED,true);doThrow(new CancellationRefusedException()).when(payment).fence(id);
        assertThatThrownBy(() -> service.compensate(id)).hasMessageContaining("409");
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(OrderSagaState.WAITING_FOR_PAYMENT);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verifyNoInteractions(inventory);
    }
    @Test void lostReleaseResponseIsDurableAndResumesAfterRestartWithoutMarkingCancelledEarly() {
        var id=seed(OrderStatus.CONFIRMED,true);
        doThrow(new InventoryServiceException("Lost response")).doNothing().when(inventory).releaseForCancellation(id,42L);
        assertThatThrownBy(() -> service.compensate(id)).isInstanceOf(InventoryServiceException.class);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(OrderSagaState.COMPENSATION_PENDING);
        service.resume(id);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventory,times(2)).releaseForCancellation(id,42L);
    }
    @Test void legacyOrderWithoutSagaStillRequiresFence() {
        var id=seed(OrderStatus.CONFIRMED,false);
        service.compensate(id);
        verify(payment).fence(id);verify(inventory).releaseForCancellation(id,42L);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }
    @Test void reservationCommittedBeforeItsLostResponseIsFoundByOrderAndReleased() {
        var order=new Order(BigDecimal.TEN,null,"test@example.test",new DeliveryAddressRequest("Jan","Test","Street","00-001","City","PL",null));
        order.addItem(1L,"SKU","Test",1,BigDecimal.TEN,BigDecimal.TEN);order=orders.saveAndFlush(order);
        var id=order.getId();sagas.saveAndFlush(new OrderSaga(id));
        when(inventory.reservationForCancellation(id)).thenReturn(91L);
        service.compensate(id);
        var sequence=inOrder(payment,inventory);sequence.verify(payment).fence(id);
        sequence.verify(inventory).reservationForCancellation(id);sequence.verify(inventory).releaseForCancellation(id,91L);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }
    @Test void anOldReservationFailureCannotOverwriteAnAlreadyPaidOrder() {
        var id=seed(OrderStatus.PAID,true);
        var saga=sagas.findByOrderId(id).orElseThrow();saga.restartReservation();sagas.saveAndFlush(saga);
        assertThat(states.claim(id,false)).isNull();
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(OrderSagaState.RECONCILIATION_REQUIRED);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(inventory,payment);
    }
}
