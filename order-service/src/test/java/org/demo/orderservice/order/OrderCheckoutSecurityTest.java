package org.demo.orderservice.order;

import org.demo.orderservice.delivery.DeliveryClient;
import org.demo.orderservice.delivery.DeliveryQuoteResponse;
import org.demo.orderservice.delivery.DeliveryServiceException;
import org.demo.orderservice.delivery.InvalidDeliverySelectionException;
import org.demo.orderservice.product.ProductClient;
import org.demo.orderservice.product.ProductResponse;
import org.demo.orderservice.promotion.PromotionClient;
import org.demo.orderservice.web.CreateOrderItemRequest;
import org.demo.orderservice.web.CreateOrderRequest;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCheckoutSecurityTest {
    private static final DeliveryAddressRequest ADDRESS = new DeliveryAddressRequest(
            "Jan", "Kowalski", "Testowa 1", "00-001", "Warszawa", "Polska", null);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    @Mock private OrderRepository orders;
    @Mock private ProductClient products;
    @Mock private PromotionClient promotions;
    @Mock private OrderSagaService sagas;
    @Mock private DeliveryClient deliveries;
    @Mock private OrderCreationService creation;
    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, products, promotions, sagas, deliveries, creation);
    }

    private void resolveProduct() {
        when(products.getProducts(List.of(101L))).thenReturn(Map.of(101L, new ProductResponse(101L, "SKU-101", "Product", "TEST",
                "MiniTech", "Test", Map.of(), new BigDecimal("100.00"), new BigDecimal("100.00"),
                0, false, false, DATE, true, Instant.now(), Instant.now())));
    }

    private CreateOrderRequest request(String fulfillment, BigDecimal submittedCost) {
        return new CreateOrderRequest(null, null, 7L, "buyer@example.com", ADDRESS,
                "STANDARD_COURIER", submittedCost, null, fulfillment, DATE, null, null,
                false, List.of(new CreateOrderItemRequest(101L, 1)));
    }

    @Test
    void usesAuthoritativeQuoteInsteadOfClientSuppliedFreeShipping() {
        resolveProduct();
        when(deliveries.quote("STANDARD_COURIER", "00-001", 1, new BigDecimal("100.00"), DATE, null))
                .thenReturn(new DeliveryQuoteResponse("STANDARD_COURIER", new BigDecimal("15.99"),
                        "HOME_DELIVERY", DATE));
        Order[] stored = new Order[1];
        when(creation.create(any(Order.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            ReflectionTestUtils.setField(stored[0], "id", 42L);
            return stored[0];
        });
        when(orders.findById(42L)).thenAnswer(ignored -> Optional.of(stored[0]));
        doAnswer(ignored -> { stored[0].changeStatus(OrderStatus.CONFIRMED);return null; }).when(sagas).resume(42L);

        var response = service.create(request("HOME_DELIVERY", BigDecimal.ZERO), "quote-test");

        assertThat(response.deliveryCost()).isEqualByComparingTo("15.99");
        assertThat(response.totalPrice()).isEqualByComparingTo("115.99");
        assertThat(response.deliveryMethod()).isEqualTo("STANDARD_COURIER");
        assertThat(response.deliveryDate()).isEqualTo(DATE);
        verify(sagas).resume(42L);
    }

    @Test
    void rejectsUnavailableDateBeforeSavingOrderOrReservingStock() {
        resolveProduct();
        when(deliveries.quote(any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new InvalidDeliverySelectionException("Unavailable date"));
        assertThatThrownBy(() -> service.create(request("HOME_DELIVERY", BigDecimal.ZERO), "bad-date"))
                .isInstanceOf(InvalidDeliverySelectionException.class);
        verifyNoInteractions(creation, sagas);
    }

    @Test
    void doesNotFallBackToClientPriceWhenDeliveryServiceIsUnavailable() {
        resolveProduct();
        when(deliveries.quote(any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new DeliveryServiceException("Offline"));
        assertThatThrownBy(() -> service.create(request("HOME_DELIVERY", BigDecimal.ZERO), "offline"))
                .isInstanceOf(DeliveryServiceException.class);
        verifyNoInteractions(creation, sagas);
    }

    @Test
    void rejectsFulfillmentTypeThatDoesNotMatchSelectedMethod() {
        resolveProduct();
        when(deliveries.quote(any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(new DeliveryQuoteResponse("STANDARD_COURIER", new BigDecimal("15.99"),
                        "HOME_DELIVERY", DATE));
        assertThatThrownBy(() -> service.create(request("STORE_PICKUP", BigDecimal.ZERO), "mismatch"))
                .isInstanceOf(InvalidDeliverySelectionException.class);
        verifyNoInteractions(creation, sagas);
    }

    @Test
    void retryOfSavedOrderDoesNotRepriceOrCreateAnotherSaga() {
        Order existing = new Order(new BigDecimal("115.99"), 7L, "buyer@example.com", ADDRESS, "saved-key");
        ReflectionTestUtils.setField(existing, "id", 42L);
        existing.bindCheckout(OrderCommand.from(request("HOME_DELIVERY",BigDecimal.ZERO),"saved-key"));
        existing.changeStatus(OrderStatus.CONFIRMED);
        when(orders.findByIdempotencyKey("saved-key")).thenReturn(Optional.of(existing));
        when(orders.findById(42L)).thenReturn(Optional.of(existing));
        assertThat(service.create(request("HOME_DELIVERY", BigDecimal.ZERO), "saved-key").id()).isEqualTo(42L);
        verifyNoInteractions(products, promotions, deliveries, creation);
        verify(sagas).requiresReconciliation(42L);verifyNoMoreInteractions(sagas);
    }

    @Test
    void rejectsOverflowWhenCombiningDuplicateProductQuantities() {
        CreateOrderRequest request = new CreateOrderRequest(null, null, null, "buyer@example.com", ADDRESS,
                "STANDARD_COURIER", BigDecimal.ZERO, null, "HOME_DELIVERY", DATE, null, null,
                false, List.of(new CreateOrderItemRequest(101L, Integer.MAX_VALUE),
                new CreateOrderItemRequest(101L, 1)));
        assertThatThrownBy(() -> service.create(request, "overflow")).isInstanceOf(ArithmeticException.class);
        verifyNoInteractions(products, deliveries, creation, sagas);
    }
}
