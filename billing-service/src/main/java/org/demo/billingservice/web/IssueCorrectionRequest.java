package org.demo.billingservice.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record IssueCorrectionRequest(
        @NotBlank(message = "Przyczyna korekty jest wymagana")
        String reason,
        @NotNull(message = "Kwota korekty brutto jest wymagana")
        @JsonAlias({"correctionGross", "correctionGrossAmount"})
        BigDecimal correctionGrossAmount
) {}
