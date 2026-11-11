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
        boolean invoiceRequested,
        Long customerId,
        String customerEmail,
        String paymentState,
        String deferredPaymentMethod,
        Long deferredPaymentId,
        boolean lifecycleReviewRequired,
        Boolean couponReservationVerified
) {
    public OrderResponse(Long id,List<OrderItemResponse> items,BigDecimal totalPrice,String status,Long inventoryReservationId,
            DeliveryAddressResponse deliveryAddress,String deliveryMethod,BigDecimal deliveryCost,String promotionCode,BigDecimal discountAmount,
            String fulfillmentType,LocalDate deliveryDate,String deliverySlot,String pickupLocation,boolean invoiceRequested,Long customerId,String customerEmail,
            String paymentState,String deferredPaymentMethod,Long deferredPaymentId,boolean lifecycleReviewRequired) {
        this(id,items,totalPrice,status,inventoryReservationId,deliveryAddress,deliveryMethod,deliveryCost,promotionCode,discountAmount,fulfillmentType,
                deliveryDate,deliverySlot,pickupLocation,invoiceRequested,customerId,customerEmail,paymentState,deferredPaymentMethod,deferredPaymentId,lifecycleReviewRequired,false);
    }
    public OrderResponse(Long id,List<OrderItemResponse> items,BigDecimal totalPrice,String status,Long inventoryReservationId,
            DeliveryAddressResponse deliveryAddress,String deliveryMethod,BigDecimal deliveryCost,String promotionCode,
            BigDecimal discountAmount,String fulfillmentType,LocalDate deliveryDate,String deliverySlot,String pickupLocation,
            boolean invoiceRequested,Long customerId,String customerEmail,String paymentState,String deferredPaymentMethod,Long deferredPaymentId) {
        this(id,items,totalPrice,status,inventoryReservationId,deliveryAddress,deliveryMethod,deliveryCost,promotionCode,
                discountAmount,fulfillmentType,deliveryDate,deliverySlot,pickupLocation,invoiceRequested,customerId,customerEmail,
                paymentState,deferredPaymentMethod,deferredPaymentId,false);
    }
    public OrderResponse(Long id,List<OrderItemResponse> items,BigDecimal totalPrice,String status,Long inventoryReservationId,
            DeliveryAddressResponse deliveryAddress,String deliveryMethod,BigDecimal deliveryCost,String promotionCode,
            BigDecimal discountAmount,String fulfillmentType,LocalDate deliveryDate,String deliverySlot,String pickupLocation,
            boolean invoiceRequested,Long customerId,String customerEmail) {
        this(id,items,totalPrice,status,inventoryReservationId,deliveryAddress,deliveryMethod,deliveryCost,promotionCode,
                discountAmount,fulfillmentType,deliveryDate,deliverySlot,pickupLocation,invoiceRequested,customerId,customerEmail,null,null,null);
    }
    public OrderResponse(Long id, List<OrderItemResponse> items, BigDecimal totalPrice, String status,
                         Long inventoryReservationId, DeliveryAddressResponse deliveryAddress, String deliveryMethod,
                         BigDecimal deliveryCost, String promotionCode, BigDecimal discountAmount, String fulfillmentType,
                         LocalDate deliveryDate, String deliverySlot, String pickupLocation, boolean invoiceRequested) {
        this(id, items, totalPrice, status, inventoryReservationId, deliveryAddress, deliveryMethod, deliveryCost,
                promotionCode, discountAmount, fulfillmentType, deliveryDate, deliverySlot, pickupLocation, invoiceRequested, null, null);
    }
    public OrderResponse(Long id, BigDecimal totalPrice, String status) {
        this(id, List.of(), totalPrice, status, null, null, null, BigDecimal.ZERO, null, BigDecimal.ZERO,
                null, null, null, null, false, null, null);
    }

    public OrderResponse(Long id, BigDecimal totalPrice, String status, DeliveryAddressResponse deliveryAddress) {
        this(id, List.of(), totalPrice, status, null, deliveryAddress, null, BigDecimal.ZERO, null, BigDecimal.ZERO,
                null, null, null, null, false, null, null);
    }

    public OrderResponse(Long id, BigDecimal totalPrice, String status, Long inventoryReservationId,
                         DeliveryAddressResponse deliveryAddress) {
        this(id, List.of(), totalPrice, status, inventoryReservationId, deliveryAddress, null, BigDecimal.ZERO,
                null, BigDecimal.ZERO, null, null, null, null, false, null, null);
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
                promotionCode, BigDecimal.ZERO, fulfillmentType, deliveryDate, deliverySlot, pickupLocation, false, null, null);
    }
}
