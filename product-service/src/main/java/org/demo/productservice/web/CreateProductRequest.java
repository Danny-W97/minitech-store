package org.demo.productservice.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record CreateProductRequest(
        @NotBlank @Size(max = 100) String sku,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 80) String category,
        @NotBlank @Size(max = 120) String manufacturer,
        @NotBlank @Size(max = 120) String model,
        Map<String, Object> technicalParameters,
        @NotNull @DecimalMin("0.00") BigDecimal price,
        Boolean active,
        @Size(max = 500) String imageUrl
) {
}
