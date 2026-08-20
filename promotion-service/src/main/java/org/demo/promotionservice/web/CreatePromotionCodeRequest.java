package org.demo.promotionservice.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

public record CreatePromotionCodeRequest(
        @Size(max = 40)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{2,39}", message = "Kod musi mieć 3–40 znaków: litery, cyfry, '-' lub '_'.")
        String code,
        @NotBlank @Size(max = 200) String description,
        @NotNull @Min(1) @Max(100) Integer discountPercent,
        @Min(1) Integer maxRedemptions,
        @NotNull LocalDate validFrom,
        @NotNull LocalDate validTo,
        @NotNull Boolean active,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal minimumOrderValue,
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal maximumDiscountAmount
) {
    public CreatePromotionCodeRequest(String code, String description, Integer discountPercent,
                                     Integer maxRedemptions, LocalDate validFrom, LocalDate validTo,
                                     Boolean active) {
        this(code, description, discountPercent, maxRedemptions, validFrom, validTo, active,
                BigDecimal.ZERO, null);
    }
}
