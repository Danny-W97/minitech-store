package org.demo.orderservice.order;

import org.demo.orderservice.delivery.DeliveryClient;
import org.demo.orderservice.delivery.DeliveryQuoteResponse;
import org.demo.orderservice.product.ProductClient;
import org.demo.orderservice.product.ProductResponse;
import org.demo.orderservice.promotion.PromotionClient;
import org.demo.orderservice.web.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CheckoutRulesTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ProductClient products = mock(ProductClient.class);
    private final PromotionClient coupons = mock(PromotionClient.class);
    private final OrderSagaService sagas = mock(OrderSagaService.class);
    private final DeliveryClient deliveries = mock(DeliveryClient.class);
    private final OrderCreationService creation = mock(OrderCreationService.class);
    private final OrderService service = new OrderService(orders, products, coupons, sagas, deliveries, creation);
    private final LocalDate date = LocalDate.of(2026, 11, 2);
    private Order saved;
    @BeforeEach void save() {
        when(creation.create(any())).thenAnswer(invocation -> {
            saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 42L);
            saved.changeStatus(OrderStatus.CONFIRMED);
            return saved;
        });
        when(orders.findById(42L)).thenAnswer(ignored -> Optional.of(saved));
    }
    private void price(String price) {
        when(products.getProducts(List.of(1L))).thenReturn(Map.of(1L, new ProductResponse(1L, "SKU", "Product", "TEST", "Brand", "Description",
                Map.of(), new BigDecimal(price), new BigDecimal(price), 0, false, false, date, true, Instant.now(), Instant.now())));
    }
    private CreateOrderRequest request(Long customer, int quantity, String method, String point) {
        return new CreateOrderRequest(null, null, customer, "buyer@example.com",
                new DeliveryAddressRequest("Jan", "Test", "Street", "00-001", "City", "PL", null),
                method, BigDecimal.ZERO, "DISCOUNT", null, date, null, point, false,
                List.of(new CreateOrderItemRequest(1L, quantity)));
    }
    private void courier() {
        when(deliveries.quote(any(), any(), anyInt(), any(), any(), any())).thenReturn(new DeliveryQuoteResponse(
                "STANDARD_COURIER", new BigDecimal("14.99"), "HOME_DELIVERY", date));
    }
    @Test void guestLimitUsesAuthoritativeProductPricesBeforeDiscountOrDelivery() {
        price("1000.01");
        assertThrows(AccountRequiredException.class, () -> service.create(request(null, 1, "STANDARD_COURIER", null)));
        verifyNoInteractions(coupons, deliveries, sagas);
        verify(creation, never()).create(any());
    }
    @Test void checksTheTotalAcrossQuantities() {
        price("500.01");
        assertThrows(AccountRequiredException.class, () -> service.create(request(null, 2, "STANDARD_COURIER", null)));
        verify(creation, never()).create(any());
    }
    @Test void exactlyOneThousandIsAvailableToGuestsEvenWhenDeliveryRaisesTheFinalTotal() {
        price("1000.00"); courier();
        assertEquals(new BigDecimal("1014.99"), service.create(request(null, 1, "STANDARD_COURIER", null)).totalPrice());
    }
    @Test void accountPurchaseMayExceedTheGuestThreshold() {
        price("1500.00"); courier();
        assertEquals(7L, service.create(request(7L, 1, "STANDARD_COURIER", null)).customerId());
    }
    @Test void persistsOnlyPickupNameReturnedByDeliveryService() {
        price("100.00");
        when(deliveries.quote(any(), any(), anyInt(), any(), any(), eq("WARSAW_STORE"))).thenReturn(new DeliveryQuoteResponse(
                "STORE_PICKUP", BigDecimal.ZERO, "STORE_PICKUP", date, "WARSAW_STORE", "Canonical store name"));
        var response = service.create(request(null, 1, "STORE_PICKUP", "WARSAW_STORE"));
        assertEquals("Canonical store name", response.pickupLocation());
        verify(deliveries).quote(eq("STORE_PICKUP"), eq("00-001"), eq(1), eq(new BigDecimal("100.00")), eq(date), eq("WARSAW_STORE"));
    }
}
