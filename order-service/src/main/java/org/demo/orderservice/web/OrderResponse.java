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
        boolean invoiceRequested,
        String paymentState,
        String deferredPaymentMethod,
        Long deferredPaymentId,
        boolean lifecycleReviewRequired,
        boolean couponReservationVerified
) {
    public OrderResponse(Long id,List<OrderItemResponse> items,BigDecimal totalPrice,OrderStatus status,Long inventoryReservationId,
            Long customerId,String customerEmail,DeliveryAddressResponse deliveryAddress,String deliveryMethod,BigDecimal deliveryCost,
            Instant createdAt,Instant updatedAt,String promotionCode,BigDecimal discountAmount,String fulfillmentType,LocalDate deliveryDate,
            String deliverySlot,String pickupLocation,boolean invoiceRequested,String paymentState,String deferredPaymentMethod,Long deferredPaymentId,boolean lifecycleReviewRequired) {
        this(id,items,totalPrice,status,inventoryReservationId,customerId,customerEmail,deliveryAddress,deliveryMethod,deliveryCost,createdAt,updatedAt,
                promotionCode,discountAmount,fulfillmentType,deliveryDate,deliverySlot,pickupLocation,invoiceRequested,paymentState,deferredPaymentMethod,deferredPaymentId,lifecycleReviewRequired,false);
    }
}
