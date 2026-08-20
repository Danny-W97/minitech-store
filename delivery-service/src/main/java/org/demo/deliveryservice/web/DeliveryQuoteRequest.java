package org.demo.deliveryservice.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DeliveryQuoteRequest(
        @NotBlank @Size(max = 40) String methodCode,
        @NotBlank @Size(max = 20) String postalCode,
        @NotNull @Positive Integer itemCount,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal subtotal,
        LocalDate requestedDate
) {
    public DeliveryQuoteRequest(
            String methodCode,
            String postalCode,
            Integer itemCount,
            BigDecimal subtotal
    ) {
        this(methodCode, postalCode, itemCount, subtotal, null);
    }
}
