package org.demo.productservice.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        String category,
        String manufacturer,
        String model,
        Map<String, Object> technicalParameters,
        BigDecimal price,
        BigDecimal regularPrice,
        int discountPercent,
        boolean promotional,
        boolean newProduct,
        LocalDate rotationDate,
        boolean active,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
