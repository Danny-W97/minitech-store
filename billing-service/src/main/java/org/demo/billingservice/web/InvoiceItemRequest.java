package org.demo.billingservice.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record InvoiceItemRequest(
        @NotNull @Positive Long productId,
        @Size(max = 100) String sku,
        @NotBlank @Size(max = 255) String productName,
        @NotNull @Positive Integer quantity,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal unitPrice,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal totalPrice
) {}
