package org.demo.orderservice.order;

import org.demo.orderservice.web.CreateOrderRequest;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderOwnershipTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderSagaService sagas = mock(OrderSagaService.class);
    private final OrderService service = new OrderService(orders, null, null, sagas, null, null);
    private final DeliveryAddressRequest address = new DeliveryAddressRequest("One", "Test", "Street", "00-001", "City", "PL", null);
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    private MockHttpServletRequest identity(Long id) {
        var request = new MockHttpServletRequest();
        request.setAttribute(Access.ACTOR, id == null ? Actor.ANONYMOUS : new Actor("CUSTOMER", null, id, "one@example.com"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
    @Test void anIdempotencyKeyDoesNotAuthorizeReadingAnotherCustomersOrder() {
        Order order = new Order(BigDecimal.TEN, 17L, "one@example.com", address, "existing-key");
        ReflectionTestUtils.setField(order, "id", 42L);
        when(orders.findByIdempotencyKey("existing-key")).thenReturn(Optional.of(order));
        var create = new CreateOrderRequest(1L, 1, 18L, "one@example.com", address, null, null, null, null, null, null, null, false, null);
        identity(18L);
        assertThrows(ResponseStatusException.class, () -> service.create(create, "existing-key"));
        identity(17L);
        var ownCreate = new CreateOrderRequest(1L, 1, 17L, "one@example.com", address, null, null, null, null, null, null, null, false, null);
        order.bindCheckout(OrderCommand.from(ownCreate,"existing-key"));
        order.changeStatus(OrderStatus.CONFIRMED);
        when(orders.findById(42L)).thenReturn(Optional.of(order));
        assertEquals(42L, service.create(ownCreate, "existing-key").id());
    }
    @Test void guestAccessUsesStoredHashAndOldUnprotectedOrdersAreOperatorOnly() {
        Order order = new Order(BigDecimal.TEN, null, "guest@example.com", address, "guest-key");
        when(orders.findById(42L)).thenReturn(Optional.of(order));
        var request = identity(null);
        assertThrows(ResponseStatusException.class, () -> service.requireOwner(42L));
        String token = "g".repeat(43);
        order.setGuestAccessTokenHash(Access.hash(token));
        request.addHeader(Access.ORDER_TOKEN, token);
        assertDoesNotThrow(() -> service.requireOwner(42L));
        assertTrue(service.ownership(42L, token).guestAllowed());
        assertFalse(service.ownership(42L, "wrong").guestAllowed());
        assertNotEquals(token, order.getGuestAccessTokenHash());
    }
}
