package org.demo.productservice.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record PatchProductRequest(
        @Size(max = 100) String sku,
        @Size(max = 200) String name,
        @Size(max = 80) String category,
        @Size(max = 120) String manufacturer,
        @Size(max = 120) String model,
        Map<String, Object> technicalParameters,
        @DecimalMin("0.00") BigDecimal price,
        Boolean active,
        @Size(max = 500) String imageUrl
) {

    public boolean isEmpty() {
        return sku == null && name == null && category == null && manufacturer == null
                && model == null && technicalParameters == null && price == null && active == null && imageUrl == null;
    }
}
