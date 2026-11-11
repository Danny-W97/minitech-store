package org.demo.orderservice.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryQuoteResponse(
        String methodCode,
        BigDecimal deliveryCost,
        String fulfillmentType,
        LocalDate selectedDate,
        String pickupLocationCode,
        String pickupLocation
) {
    public DeliveryQuoteResponse(String methodCode, BigDecimal deliveryCost, String fulfillmentType, LocalDate selectedDate) {
        this(methodCode, deliveryCost, fulfillmentType, selectedDate, null, null);
    }
}
