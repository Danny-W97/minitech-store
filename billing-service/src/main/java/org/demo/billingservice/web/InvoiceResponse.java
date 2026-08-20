package org.demo.billingservice.web;

import org.demo.billingservice.billing.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceResponse(Long id, Long orderId, Long customerId, String customerEmail, String invoiceNumber,
                              BillingKind billingKind, String buyerName, String companyName, String taxId,
                              String street, String postalCode, String city, String country,
                              BigDecimal netAmount, BigDecimal vatAmount, BigDecimal grossAmount,
                              BigDecimal deliveryCost, BigDecimal discountAmount, List<InvoiceItemResponse> items,
                              String currency, InvoiceStatus status, Instant createdAt) {
    public static InvoiceResponse from(Invoice i) {
        return new InvoiceResponse(i.getId(), i.getOrderId(), i.getCustomerId(), i.getCustomerEmail(), i.getInvoiceNumber(), i.getBillingKind(),
                i.getBuyerName(), i.getCompanyName(), i.getTaxId(), i.getStreet(), i.getPostalCode(), i.getCity(), i.getCountry(),
                i.getNetAmount(), i.getVatAmount(), i.getGrossAmount(), i.getDeliveryCost(), i.getDiscountAmount(),
                i.getItems().stream().map(InvoiceItemResponse::from).toList(), i.getCurrency(), i.getStatus(), i.getCreatedAt());
    }
}
