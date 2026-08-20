package org.demo.orderservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreateOrderRequest(
        // Legacy single-line fields remain accepted for clients created before
        // multi-item checkout. New clients should send items instead.
        @Positive Long productId,
        @Positive Integer quantity,
        Long customerId,
        @NotBlank @Email @Size(max = 255) String customerEmail,
        @NotNull @Valid DeliveryAddressRequest deliveryAddress,
        @Size(max = 40) String deliveryMethod,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal deliveryCost,
        @Size(max = 40) String promotionCode,
        @Size(max = 30) String fulfillmentType,
        LocalDate deliveryDate,
        @Size(max = 40) String deliverySlot,
        @Size(max = 80) String pickupLocation,
        Boolean invoiceRequested,
        List<@Valid CreateOrderItemRequest> items
) {
}
