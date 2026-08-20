package org.demo.paymentservice.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OrderResponse(
        Long id,
        List<OrderItemResponse> items,
        BigDecimal totalPrice,
        String status,
        Long inventoryReservationId,
        DeliveryAddressResponse deliveryAddress,
        String deliveryMethod,
        BigDecimal deliveryCost,
        String promotionCode,
        BigDecimal discountAmount,
        String fulfillmentType,
        LocalDate deliveryDate,
        String deliverySlot,
        String pickupLocation,
        boolean invoiceRequested
) {
    public OrderResponse(Long id, BigDecimal totalPrice, String status) {
        this(id, List.of(), totalPrice, status, null, null, null, BigDecimal.ZERO, null, BigDecimal.ZERO,
                null, null, null, null, false);
    }

    public OrderResponse(Long id, BigDecimal totalPrice, String status, DeliveryAddressResponse deliveryAddress) {
        this(id, List.of(), totalPrice, status, null, deliveryAddress, null, BigDecimal.ZERO, null, BigDecimal.ZERO,
                null, null, null, null, false);
    }

    public OrderResponse(Long id, BigDecimal totalPrice, String status, Long inventoryReservationId,
                         DeliveryAddressResponse deliveryAddress) {
        this(id, List.of(), totalPrice, status, inventoryReservationId, deliveryAddress, null, BigDecimal.ZERO,
                null, BigDecimal.ZERO, null, null, null, null, false);
    }

    /**
     * Compatibility constructor for payment-service tests and callers created
     * before invoiceRequested became part of the order contract.
     */
    public OrderResponse(Long id, BigDecimal totalPrice, String status, Long inventoryReservationId,
                         DeliveryAddressResponse deliveryAddress, String deliveryMethod,
                         String promotionCode, String fulfillmentType, LocalDate deliveryDate,
                         String deliverySlot, String pickupLocation) {
        this(id, List.of(), totalPrice, status, inventoryReservationId, deliveryAddress, deliveryMethod, BigDecimal.ZERO,
                promotionCode, BigDecimal.ZERO, fulfillmentType, deliveryDate, deliverySlot, pickupLocation, false);
    }
}
