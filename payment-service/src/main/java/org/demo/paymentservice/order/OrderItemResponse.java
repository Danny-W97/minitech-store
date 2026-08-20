package org.demo.paymentservice.order;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        Long productId,
        String sku,
        String productName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice
) {}
