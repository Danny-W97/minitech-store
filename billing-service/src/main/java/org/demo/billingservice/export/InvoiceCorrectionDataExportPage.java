package org.demo.billingservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceCorrectionDataExportPage(int schemaVersion,String scope,boolean complete,Instant generatedAt,
                                              Long orderId,List<Correction> corrections,boolean hasMore,String nextCursor,
                                              List<InvoiceDataExport.Omission> omissions) {
    @Schema(name="InvoiceExportCorrection")
    public record Correction(Long correctionId,Long invoiceId,Long orderId,String correctionNumber,String reason,
                             BigDecimal originalNet,BigDecimal originalVat,BigDecimal originalGross,
                             BigDecimal correctionNet,BigDecimal correctionVat,BigDecimal correctionGross,
                             BigDecimal finalNet,BigDecimal finalVat,BigDecimal finalGross,Instant createdAt,
                             String balanceModel,InvoiceDataExport.Document pdf) {}
}
