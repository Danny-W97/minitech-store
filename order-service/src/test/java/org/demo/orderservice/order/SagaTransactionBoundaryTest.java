package org.demo.orderservice.order;

import org.demo.orderservice.inventory.*;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a31sagas;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.hikari.maximum-pool-size=1","order.saga.recovery-enabled=false"})
class SagaTransactionBoundaryTest {
    @Autowired OrderCreationService creation;
    @Autowired OrderRepository orders;
    @Autowired OrderSagaService sagas;
    @MockitoBean InventoryClient inventory;
    private Order create() {
        var order=new Order(BigDecimal.TEN,null,"owner@example.test",new DeliveryAddressRequest("Jan","Nowak","Test 1","00-001","Warszawa","PL",null));
        order.addItem(1L,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN); return creation.create(order);
    }
    @Test void oneSlowSagaDoesNotHoldTheOnlyConnectionOrBlockOtherSagas() throws Exception {
        var slow=create(); var fast=create(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(inventory.reserve(anyLong(),anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            long id=call.getArgument(0);
            if(id==slow.getId()) { entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout"); }
            return new InventoryReservationResponse(1000+id,id,"RESERVED",null);
        });
        sagas.resumeDueSagas();
        try {
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(orders.findById(fast.getId()).orElseThrow().getStatus()!=OrderStatus.CONFIRMED && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(orders.findById(fast.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(orders.count()).isEqualTo(2);
            assertThat(orders.findById(slow.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
        } finally { release.countDown(); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(orders.findById(slow.getId()).orElseThrow().getStatus()!=OrderStatus.CONFIRMED && System.nanoTime()<deadline) Thread.sleep(10);
        assertThat(orders.findById(slow.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
