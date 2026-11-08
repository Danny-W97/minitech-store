package org.demo.paymentservice.web;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CollectPaymentRequest(@NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal amount,
        @NotBlank @Pattern(regexp="PLN") String currency,
        @NotBlank @Size(min=3,max=80) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{2,79}") String receiptReference) {}
