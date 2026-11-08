package org.demo.billingservice.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record IssueCorrectionRequest(
        @NotBlank(message = "Przyczyna korekty jest wymagana")
        @Size(max=255)
        @Pattern(regexp="[^\\p{Cntrl}]+")
        String reason,
        @NotNull(message = "Kwota korekty brutto jest wymagana")
        @JsonAlias({"correctionGross", "correctionGrossAmount"})
        @Digits(integer=17,fraction=2)
        BigDecimal correctionGrossAmount,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String idempotencyKey,
        @NotNull @PositiveOrZero Long expectedVersion
) {}
