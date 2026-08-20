package org.demo.billingservice.web;

import org.demo.billingservice.billing.InvoiceItem;

import java.math.BigDecimal;

public record InvoiceItemResponse(Long productId, String sku, String productName, Integer quantity,
                                  BigDecimal unitPrice, BigDecimal totalPrice) {
    public static InvoiceItemResponse from(InvoiceItem item) {
        return new InvoiceItemResponse(item.getProductId(), item.getSku(), item.getProductName(), item.getQuantity(),
                item.getUnitPrice(), item.getTotalPrice());
    }
}
