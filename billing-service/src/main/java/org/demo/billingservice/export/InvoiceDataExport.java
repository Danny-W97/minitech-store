package org.demo.billingservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceDataExport(int schemaVersion,String scope,boolean complete,Instant generatedAt,
                                 InvoiceData invoice,Document pdf,List<Omission> omissions) {
    @Schema(name="InvoiceExportData")
    public record InvoiceData(Long invoiceId,Long orderId,String customerEmail,String invoiceNumber,String billingKind,
                              String buyerName,String companyName,String taxId,String street,String postalCode,String city,String country,
                              BigDecimal netAmount,BigDecimal vatAmount,BigDecimal grossAmount,BigDecimal deliveryCost,
                              BigDecimal discountAmount,String currency,String status,Instant createdAt) {}
    @Schema(name="InvoiceExportDocument")
    public record Document(String path,String mediaType,String representation) {}
    @Schema(name="InvoiceExportOmission")
    public record Omission(String scope,String status) {}
}
