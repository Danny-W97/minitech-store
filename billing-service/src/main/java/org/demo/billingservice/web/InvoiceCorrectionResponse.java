package org.demo.billingservice.web;

import org.demo.billingservice.billing.InvoiceCorrection;
import java.math.BigDecimal;
import java.time.Instant;

public record InvoiceCorrectionResponse(
        Long id,
        Long invoiceId,
        Long orderId,
        String correctionNumber,
        String originalInvoiceNumber,
        String reason,
        BigDecimal originalNet,
        BigDecimal originalVat,
        BigDecimal originalGross,
        BigDecimal correctionNet,
        BigDecimal correctionVat,
        BigDecimal correctionGross,
        BigDecimal finalNet,
        BigDecimal finalVat,
        BigDecimal finalGross,
        Instant createdAt,
        Long balanceVersion,
        String balanceModel,
        String idempotencyKey
) {
    public static InvoiceCorrectionResponse from(InvoiceCorrection c) {
        return new InvoiceCorrectionResponse(
                c.getId(),
                c.getInvoice().getId(),
                c.getOrderId(),
                c.getCorrectionNumber(),
                c.getInvoice().getInvoiceNumber(),
                c.getReason(),
                c.getOriginalNet(),
                c.getOriginalVat(),
                c.getOriginalGross(),
                c.getCorrectionNet(),
                c.getCorrectionVat(),
                c.getCorrectionGross(),
                c.getFinalNet(),
                c.getFinalVat(),
                c.getFinalGross(),
                c.getCreatedAt(),
                c.getLedgerVersion(),
                c.getLedgerVersion()==null ? "LEGACY_BASE_DELTA" : "CUMULATIVE_DELTA",
                c.getIdempotencyKey()
        );
    }
}
