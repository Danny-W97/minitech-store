package org.demo.billingservice.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record IssueInvoiceRequest(
        @NotNull @Positive Long orderId,
        @Positive Long customerId,
        @NotNull @Email @Size(max = 255) String customerEmail,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal grossAmount,
        @Size(max = 3) String currency,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal deliveryCost,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal discountAmount,
        @NotEmpty @Size(max = 100) List<@Valid InvoiceItemRequest> items,
        @NotNull @Valid BillingData billingData
) {}
