package org.demo.deliveryservice.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DeliveryQuoteResponse(
        Long methodId,
        String methodCode,
        String name,
        String description,
        BigDecimal deliveryCost,
        BigDecimal subtotal,
        BigDecimal totalPrice,
        boolean freeDelivery,
        int etaMinDays,
        int etaMaxDays,
        String fulfillmentType,
        List<LocalDate> availableDates,
        LocalDate selectedDate
) {
}
