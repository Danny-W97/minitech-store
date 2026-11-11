package org.demo.orderservice.order;

import org.demo.orderservice.web.*;
import org.demo.orderservice.product.*;
import org.demo.orderservice.delivery.*;
import org.demo.orderservice.promotion.PromotionClient;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderIdempotencyTest {
    private final OrderRepository orders=mock(OrderRepository.class);
    private final ProductClient products=mock(ProductClient.class);
    private final DeliveryClient delivery=mock(DeliveryClient.class);
    private final PromotionClient coupons=mock(PromotionClient.class);
    private final OrderSagaService sagas=mock(OrderSagaService.class);
    private final OrderCreationService creation=mock(OrderCreationService.class);
    private final OrderService service=new OrderService(orders,products,coupons,sagas,delivery,creation);
    private final DeliveryAddressRequest address=new DeliveryAddressRequest("Jan","Test","Street","00-001","City","PL",null);
    private CreateOrderRequest request(List<CreateOrderItemRequest> items) {
        return new CreateOrderRequest(null,null,17L,"owner@example.test",address,"STANDARD_COURIER",null,null,null,null,null,null,false,items);
    }
    private CreateOrderRequest request() { return request(List.of(new CreateOrderItemRequest(1L,1))); }
    private Order stored(CreateOrderRequest request,boolean confirmed,boolean legacy) {
        var value=new Order(BigDecimal.TEN,17L,"owner@example.test",address,"stable-key");ReflectionTestUtils.setField(value,"id",42L);
        if(!legacy) value.bindCheckout(OrderCommand.from(request,"stable-key"));
        if(confirmed) value.changeStatus(OrderStatus.CONFIRMED);
        when(orders.findById(42L)).thenReturn(Optional.of(value));return value;
    }
    @Test void equivalentCartAndDefaultsHaveTheSameIntentIndependentOfOrderAndDuplicateLines() {
        var a=request(List.of(new CreateOrderItemRequest(2L,1),new CreateOrderItemRequest(1L,1),new CreateOrderItemRequest(1L,2)));
        var b=request(List.of(new CreateOrderItemRequest(1L,3),new CreateOrderItemRequest(2L,1)));
        assertThat(OrderCommand.from(a,"one").fingerprint()).isEqualTo(OrderCommand.from(b,"two").fingerprint());
        var legacy=new CreateOrderRequest(1L,1,17L,"OWNER@example.test",address,null,new BigDecimal("999"),null,"HOME_DELIVERY",null,"",null,null,null);
        assertThat(OrderCommand.from(legacy,"k").fingerprint()).isEqualTo(OrderCommand.from(request(),"k").fingerprint());
    }
    @Test void businessFieldsAreBoundButUntrustedDeliveryCostDoesNotRepriceTheExistingOrder() {
        var base=request();var original=OrderCommand.from(base,"k").fingerprint();
        for(var changed:List.of(
                new CreateOrderRequest(null,null,18L,base.customerEmail(),address,base.deliveryMethod(),null,null,null,null,null,null,false,base.items()),
                new CreateOrderRequest(null,null,17L,"other@example.test",address,base.deliveryMethod(),null,null,null,null,null,null,false,base.items()),
                new CreateOrderRequest(null,null,17L,base.customerEmail(),new DeliveryAddressRequest("Jan","Test","Other","00-001","City","PL",null),base.deliveryMethod(),null,null,null,null,null,null,false,base.items()),
                new CreateOrderRequest(null,null,17L,base.customerEmail(),address,"STORE_PICKUP",null,null,null,null,null,"POINT",false,base.items()),
                new CreateOrderRequest(null,null,17L,base.customerEmail(),address,base.deliveryMethod(),null,"CODE",null,null,null,null,false,base.items()),
                new CreateOrderRequest(null,null,17L,base.customerEmail(),address,base.deliveryMethod(),null,null,null,LocalDate.of(2027,1,1),null,null,false,base.items()),
                new CreateOrderRequest(null,null,17L,base.customerEmail(),address,base.deliveryMethod(),null,null,null,null,null,null,true,base.items()),
                request(List.of(new CreateOrderItemRequest(1L,2)))))
            assertThat(OrderCommand.from(changed,"k").fingerprint()).isNotEqualTo(original);
    }
    @Test void stableReplayUsesStoredQuoteWithoutDependencyCallsOrANewSaga() {
        var row=stored(request(),true,false);when(orders.findByIdempotencyKey("stable-key")).thenReturn(Optional.of(row));
        assertThat(service.create(request(),"stable-key").id()).isEqualTo(42L);
        verifyNoInteractions(products,delivery,coupons,creation);verify(sagas,never()).resume(any());
    }
    @Test void changedIntentAndLegacyKeysNeverStartAnotherCheckout() {
        var row=stored(request(),true,false);when(orders.findByIdempotencyKey("stable-key")).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.create(request(List.of(new CreateOrderItemRequest(1L,2))),"stable-key"))
                .isInstanceOf(OrderIdempotencyException.class).hasMessage("ORDER_IDEMPOTENCY_CONFLICT");
        var old=stored(request(),true,true);when(orders.findByIdempotencyKey("stable-key")).thenReturn(Optional.of(old));
        assertThatThrownBy(() -> service.create(request(),"stable-key")).hasMessage("ORDER_IDEMPOTENCY_LEGACY_REVIEW");
        verifyNoInteractions(products,delivery,coupons,creation,sagas);
    }
    @Test void concurrentInsertConflictIsReadOutsideTheFailedTransaction() {
        var winner=stored(request(),true,false);
        when(orders.findByIdempotencyKey("stable-key")).thenReturn(Optional.empty(),Optional.of(winner));
        when(products.getProducts(List.of(1L))).thenReturn(Map.of(1L,new ProductResponse(1L,"SKU","Product","Test","Brand","Model",Map.of(),BigDecimal.TEN,BigDecimal.TEN,0,false,false,LocalDate.now(),true,Instant.now(),Instant.now())));
        when(delivery.quote(any(),any(),anyInt(),any(),any(),any())).thenReturn(new DeliveryQuoteResponse("STANDARD_COURIER",BigDecimal.ZERO,"HOME_DELIVERY",LocalDate.now()));
        when(creation.create(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("Concurrent key"));
        assertThat(service.create(request(),"stable-key").id()).isEqualTo(42L);verify(sagas,never()).resume(any());
    }
    @Test void winnerCommittedDuringAQuoteFailureStillProvidesTheCanonicalResult() {
        var row=stored(request(),true,false);when(orders.findByIdempotencyKey("stable-key")).thenReturn(Optional.empty(),Optional.of(row));
        when(products.getProducts(any())).thenThrow(new IllegalStateException("Quote lost"));
        assertThat(service.create(request(),"stable-key").totalPrice()).isEqualByComparingTo("10");verifyNoInteractions(creation,delivery,coupons);
    }
    @Test void activeSagaReturnsRetryableConflictAndTheSameKeyCanResumeAfterItsLease() {
        var row=stored(request(),false,false);when(orders.findByIdempotencyKey("stable-key")).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.create(request(),"stable-key")).hasMessage("ORDER_IN_PROGRESS");
        doAnswer(ignored -> {row.changeStatus(OrderStatus.CONFIRMED);return null;}).when(sagas).resume(42L);
        assertThat(service.create(request(),"stable-key").id()).isEqualTo(42L);verifyNoInteractions(products,delivery,coupons,creation);
    }
    @Test void invalidKeysAreRejectedBeforeAnyLookupOrRemoteWork() {
        for(String key:List.of(""," ","a b","x".repeat(101),"é","bad\nkey"))
            assertThatThrownBy(() -> service.create(request(),key)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orders,products,delivery,coupons,creation,sagas);
        assertThat(OrderCommand.from(request(),"x".repeat(100)).key()).hasSize(100);
    }
}
