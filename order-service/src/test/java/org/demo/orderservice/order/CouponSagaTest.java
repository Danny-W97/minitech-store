package org.demo.orderservice.order;

import org.demo.orderservice.inventory.*;
import org.demo.orderservice.payment.*;
import org.demo.orderservice.promotion.*;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:coupon-saga;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","order.saga.recovery-enabled=false"})
class CouponSagaTest {
    @Autowired OrderCreationService creation;
    @Autowired OrderRepository orders;
    @Autowired OrderSagaRepository sagaRows;
    @Autowired OrderSagaService sagas;
    @Autowired OrderCouponService coupons;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean InventoryClient inventory;
    @MockitoBean PromotionClient promotions;
    @MockitoBean PaymentCancellationClient payment;
    @BeforeEach void clear() { sagaRows.deleteAll();orders.deleteAll();reset(inventory,promotions,payment); }
    private Order seed() {
        var order=new Order(new BigDecimal("9"),17L,"owner@example.test",new DeliveryAddressRequest("Jan","Test","Street","00-001","City","PL",null),
                "STANDARD_COURIER",BigDecimal.ZERO,java.util.UUID.randomUUID().toString(),"LIMIT-ONE",BigDecimal.ONE,"HOME_DELIVERY",null,null,null);
        order.addItem(1L,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN);return creation.create(order);
    }
    private void confirmed(Order order) {
        when(inventory.reserve(eq(order.getId()),anyList())).thenReturn(new InventoryReservationResponse(42L,order.getId(),"RESERVED",null));sagas.resume(order.getId());
    }
    private void due(Long id) { jdbc.update("update orders set coupon_expires_at=CURRENT_TIMESTAMP-INTERVAL '1' SECOND,coupon_next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1' SECOND where id=?",id); }
    @Test void couponCapacityIsConfirmedBeforeStockAndBeforeOrderConfirmation() {
        var order=seed();doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);return null;
        }).when(promotions).reserve(eq("LIMIT-ONE"),eq(order.getId()),any(),any(),any(),any());
        confirmed(order);var sequence=inOrder(promotions,inventory);
        sequence.verify(promotions).reserve(eq("LIMIT-ONE"),eq(order.getId()),any(),any(),any(),any());sequence.verify(inventory).reserve(eq(order.getId()),anyList());
        var saved=orders.findById(order.getId()).orElseThrow();assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(saved.isCouponReservationVerified()).isTrue();assertThat(saved.getCouponExpiresAt()).isNotNull();
    }
    @Test void losingTheLastCouponNeverConfirmsStockAndCompensatesThroughAFence() {
        var order=seed();when(promotions.reserve(any(),any(),any(),any(),any(),any())).thenThrow(new CouponReservationConflictException());
        assertThatThrownBy(() -> sagas.resume(order.getId())).hasMessageContaining("409");
        verify(inventory,never()).reserve(any(),any());assertThat(orders.findById(order.getId()).orElseThrow().isCouponReservationVerified()).isFalse();
        assertThat(sagaRows.findByOrderId(order.getId()).orElseThrow().getState()).isEqualTo(OrderSagaState.COMPENSATION_PENDING);
        sagas.resume(order.getId());var sequence=inOrder(payment,promotions);sequence.verify(payment).fence(order.getId());sequence.verify(promotions).release("LIMIT-ONE",order.getId());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }
    @Test void uncertainPaymentStopsExpiryBeforeCouponOrStockRelease() {
        var order=seed();confirmed(order);due(order.getId());doThrow(new CancellationRefusedException()).when(payment).fence(order.getId());
        assertThatThrownBy(() -> coupons.expire(order.getId())).isInstanceOf(CancellationRefusedException.class);
        verify(promotions,never()).release(any(),any());verify(inventory,never()).releaseForCancellation(any(),any());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
    @Test void expiryReleasesCouponOnlyAfterPaymentFenceAndThenReleasesStock() {
        var order=seed();confirmed(order);due(order.getId());coupons.expire(order.getId());
        var sequence=inOrder(payment,promotions,inventory);sequence.verify(payment).fence(order.getId());sequence.verify(promotions).release("LIMIT-ONE",order.getId());sequence.verify(inventory).releaseForCancellation(order.getId(),42L);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }
    @Test void couponReleaseFailureLeavesCancellationRecoverableWithoutAFalseCancelledOrder() {
        var order=seed();confirmed(order);doThrow(new PromotionServiceException("temporarily unavailable")).when(promotions).release("LIMIT-ONE",order.getId());
        assertThatThrownBy(() -> sagas.compensate(order.getId())).hasMessageContaining("temporarily unavailable");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(sagaRows.findByOrderId(order.getId()).orElseThrow().getState()).isEqualTo(OrderSagaState.COMPENSATION_PENDING);
        doNothing().when(promotions).release("LIMIT-ONE",order.getId());sagas.resume(order.getId());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }
}
