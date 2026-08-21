package org.demo.orderservice.order;

import org.demo.orderservice.product.ProductClient;
import org.demo.orderservice.product.ProductResponse;
import org.demo.orderservice.promotion.PromotionClient;
import org.demo.orderservice.web.CreateOrderItemRequest;
import org.demo.orderservice.web.CreateOrderRequest;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.demo.orderservice.web.OrderResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiItemOrderTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductClient productClient;
    @Mock
    private PromotionClient promotionClient;
    @Mock
    private OrderSagaService orderSagaService;

    @Test
    void createsOneOrderContainingAllCartLines() throws Exception {
        ProductResponse headphones = product(1L, "AUDIO-001", "Headphones", "99.99");
        ProductResponse processor = product(2L, "CPU-002", "Processor", "49.50");
        when(orderRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(productClient.getProduct(1L)).thenReturn(headphones);
        when(productClient.getProduct(2L)).thenReturn(processor);

        Order[] savedOrder = new Order[1];
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            savedOrder[0] = invocation.getArgument(0);
            setId(savedOrder[0], 42L);
            return savedOrder[0];
        });
        when(orderRepository.findById(42L)).thenAnswer(invocation -> Optional.of(savedOrder[0]));

        CreateOrderRequest request = new CreateOrderRequest(
                null,
                null,
                7L,
                "buyer@example.com",
                new DeliveryAddressRequest("Jan", "Kowalski", "Testowa 1", "00-001", "Warszawa", "Polska", null),
                "STANDARD_COURIER",
                new BigDecimal("9.99"),
                null,
                "HOME_DELIVERY",
                LocalDate.of(2026, 8, 21),
                null,
                null,
                false,
                List.of(new CreateOrderItemRequest(1L, 2), new CreateOrderItemRequest(2L, 1))
        );

        OrderResponse response = new OrderService(
                orderRepository, productClient, promotionClient, orderSagaService
        ).create(request, "checkout-42");

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).productName()).isEqualTo("Headphones");
        assertThat(response.items().get(0).quantity()).isEqualTo(2);
        assertThat(response.items().get(1).productName()).isEqualTo("Processor");
        assertThat(response.totalPrice()).isEqualByComparingTo("259.47");
    }

    private static ProductResponse product(Long id, String sku, String name, String price) {
        Instant now = Instant.now();
        return new ProductResponse(
                id, sku, name, "TEST", "MiniTech", name, Map.of(),
                new BigDecimal(price), new BigDecimal(price), 0, false, false,
                LocalDate.of(2026, 8, 20), true, now, now
        );
    }

    private static void setId(Order order, Long id) throws Exception {
        Field field = Order.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(order, id);
    }
}
