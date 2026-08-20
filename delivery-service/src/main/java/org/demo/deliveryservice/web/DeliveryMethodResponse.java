package org.demo.deliveryservice.web;

import java.math.BigDecimal;

public record DeliveryMethodResponse(
        Long id,
        String code,
        String name,
        String description,
        BigDecimal basePrice,
        BigDecimal freeDeliveryFrom,
        int etaMinDays,
        int etaMaxDays,
        boolean active
) {
}
