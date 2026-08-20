package org.demo.orderservice.web;

import org.demo.orderservice.order.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OrderResponse(
        Long id,
        List<OrderItemResponse> items,
        BigDecimal totalPrice,
        OrderStatus status,
        Long inventoryReservationId,
        Long customerId,
        String customerEmail,
        DeliveryAddressResponse deliveryAddress,
        String deliveryMethod,
        BigDecimal deliveryCost,
        Instant createdAt,
        Instant updatedAt,
        String promotionCode,
        BigDecimal discountAmount,
        String fulfillmentType,
        LocalDate deliveryDate,
        String deliverySlot,
        String pickupLocation,
        boolean invoiceRequested
) {
}
